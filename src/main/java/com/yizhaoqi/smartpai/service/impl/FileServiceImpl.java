package com.yizhaoqi.smartpai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 文件服务实现类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final DocumentService documentService;
    private final DeepSeekClient deepSeekClient;

    @Override
    public Map<String, Object> analyzeFile(String fileMd5) {
        // 1. 获取文件内容
        String fileContent = documentService.getFileContent(fileMd5);
        
        // 2. 使用AI分析文件
        String analysis = analyzeWithAI(fileContent);
        
        // 3. 解析AI分析结果
        Map<String, Object> analysisResult = parseAnalysisResult(analysis, fileContent);

        return analysisResult;
    }

    /**
     * 使用AI分析文件
     */
    private String analyzeWithAI(String fileContent) {
        // 限制文件内容长度，避免超过API限制
        String contentToAnalyze = fileContent.length() > 5000 ? fileContent.substring(0, 5000) + "... (内容已截断)" : fileContent;
        
        String prompt = "请仔细分析以下文件内容，并根据文件的实际内容和特点，按照以下JSON格式返回详细的分析结果：\n" +
                "{\n" +
                "  \"summary\": \"根据文件实际内容撰写的详细总结，包括文件的主要内容、目的和核心信息\",\n" +
                "  \"keyPoints\": [\"根据文件内容提取的具体关键点1\", \"根据文件内容提取的具体关键点2\", \"根据文件内容提取的具体关键点3\"],\n" +
                "  \"suggestions\": [\"针对文件内容的具体改进建议1\", \"针对文件内容的具体改进建议2\", \"针对文件内容的具体改进建议3\"]\n" +
                "}\n\n" +
                "分析要求：\n" +
                "1. 总结部分要详细且有针对性，避免泛泛而谈\n" +
                "2. 关键点要具体，直接来源于文件内容，不要使用模板化语言\n" +
                "3. 建议要切实可行，与文件内容相关\n" +
                "4. 确保分析结果与文件内容高度相关，避免生成与文件无关的内容\n" +
                "5. 保持语言简洁明了，避免重复和冗余\n\n" +
                "文件内容：\n" +
                contentToAnalyze;
        
        return deepSeekClient.sendMessage(prompt);
    }

    /**
     * 解析AI分析结果
     */
    private Map<String, Object> parseAnalysisResult(String aiResponse, String originalContent) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 清理AI响应中的Markdown格式，特别是反引号
            String cleanedResponse = aiResponse.trim()
                    .replaceAll("^```json", "")
                    .replaceAll("```$", "")
                    .trim();
            
            // 尝试解析AI返回的JSON格式响应
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(cleanedResponse);
            
            // 提取总结
            String summary = jsonNode.path("summary").asText("文件分析总结未提供");
            result.put("summary", summary);
            
            // 提取关键点
            List<String> keyPoints = new ArrayList<>();
            JsonNode keyPointsNode = jsonNode.path("keyPoints");
            if (keyPointsNode.isArray()) {
                for (JsonNode node : keyPointsNode) {
                    keyPoints.add(node.asText());
                }
            }
            // 如果没有关键点，返回默认值
            if (keyPoints.isEmpty()) {
                keyPoints.add("文件包含重要信息");
                keyPoints.add("文件有明确的结构");
                keyPoints.add("文件内容完整");
            }
            result.put("keyPoints", keyPoints);
            
            // 提取建议
            List<String> suggestions = new ArrayList<>();
            JsonNode suggestionsNode = jsonNode.path("suggestions");
            if (suggestionsNode.isArray()) {
                for (JsonNode node : suggestionsNode) {
                    suggestions.add(node.asText());
                }
            }
            // 如果没有建议，返回默认值
            if (suggestions.isEmpty()) {
                suggestions.add("可以进一步完善文件内容");
                suggestions.add("考虑添加更多详细信息");
                suggestions.add("优化文件结构");
            }
            result.put("suggestions", suggestions);
            
        } catch (Exception e) {
            // 如果解析失败，使用默认值
            log.error("解析AI响应失败: {}", e.getMessage(), e);
            result.put("summary", "文件分析总结未提供");
            result.put("keyPoints", Arrays.asList(
                    "文件包含重要信息",
                    "文件有明确的结构",
                    "文件内容完整"
            ));
            result.put("suggestions", Arrays.asList(
                    "可以进一步完善文件内容",
                    "考虑添加更多详细信息",
                    "优化文件结构"
            ));
        }
        
        result.put("rawData", Map.of(
                "originalContentLength", originalContent.length(),
                "aiResponse", aiResponse
        ));

        return result;
    }
}
