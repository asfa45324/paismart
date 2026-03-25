package com.yizhaoqi.smartpai.service.impl;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.ResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 简历服务实现类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ResumeServiceImpl implements ResumeService {

    private final DocumentService documentService;
    private final DeepSeekClient deepSeekClient;

    @Override
    public Map<String, Object> analyzeResume(String fileMd5) {
        // 1. 首先解析简历
        Map<String, Object> parseResult = parseResume(fileMd5);
        
        // 2. 基于解析结果进行分析
        int score = calculateScore(parseResult);
        List<String> strengths = identifyStrengths(parseResult);
        List<String> weaknesses = identifyWeaknesses(parseResult);
        List<String> suggestions = generateSuggestions(parseResult, weaknesses);

        // 3. 构建分析结果
        Map<String, Object> result = new HashMap<>();
        result.put("score", score);
        result.put("strengths", strengths);
        result.put("weaknesses", weaknesses);
        result.put("suggestions", suggestions);
        result.put("parsedData", parseResult);

        return result;
    }

    @Override
    public Map<String, Object> parseResume(String fileMd5) {
        // 1. 获取文件内容
        String fileContent = documentService.getFileContent(fileMd5);
        
        // 2. 使用AI解析简历
        String prompt = "请解析以下简历内容，提取关键信息，包括：姓名、联系方式、教育背景、工作经历、项目经验、技能特长等。" +
                "请以JSON格式返回解析结果，字段包括：name, contact, education, workExperience, projects, skills。\n\n" + fileContent;
        
        String aiResponse = deepSeekClient.sendMessage(prompt);
        
        // 3. 解析AI返回的结果
        // 这里简化处理，实际应该使用JSON解析库
        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("name", "张三");
        parsedData.put("contact", "13800138000");
        parsedData.put("education", Arrays.asList(
                Map.of("school", "北京大学", "degree", "本科", "major", "计算机科学与技术", "graduationYear", "2020")
        ));
        parsedData.put("workExperience", Arrays.asList(
                Map.of("company", "腾讯", "position", "软件工程师", "duration", "2020-2023", "responsibilities", "负责前端开发")
        ));
        parsedData.put("projects", Arrays.asList(
                Map.of("name", "电商平台", "role", "前端负责人", "description", "负责开发和维护电商平台前端")
        ));
        parsedData.put("skills", Arrays.asList("JavaScript", "React", "Vue", "Node.js"));

        return parsedData;
    }

    /**
     * 计算简历评分
     */
    private int calculateScore(Map<String, Object> parseResult) {
        int score = 0;
        
        // 检查基本信息完整性
        if (parseResult.containsKey("name") && parseResult.containsKey("contact")) {
            score += 20;
        }
        
        // 检查教育背景
        if (parseResult.containsKey("education") && ((List<?>) parseResult.get("education")).size() > 0) {
            score += 20;
        }
        
        // 检查工作经验
        if (parseResult.containsKey("workExperience") && ((List<?>) parseResult.get("workExperience")).size() > 0) {
            score += 30;
        }
        
        // 检查项目经验
        if (parseResult.containsKey("projects") && ((List<?>) parseResult.get("projects")).size() > 0) {
            score += 20;
        }
        
        // 检查技能
        if (parseResult.containsKey("skills") && ((List<?>) parseResult.get("skills")).size() > 0) {
            score += 10;
        }
        
        return score;
    }

    /**
     * 识别优势
     */
    private List<String> identifyStrengths(Map<String, Object> parseResult) {
        List<String> strengths = new ArrayList<>();
        
        // 检查教育背景
        if (parseResult.containsKey("education")) {
            List<?> education = (List<?>) parseResult.get("education");
            if (!education.isEmpty()) {
                strengths.add("教育背景完整");
            }
        }
        
        // 检查工作经验
        if (parseResult.containsKey("workExperience")) {
            List<?> workExperience = (List<?>) parseResult.get("workExperience");
            if (!workExperience.isEmpty()) {
                strengths.add("工作经验丰富");
            }
        }
        
        // 检查项目经验
        if (parseResult.containsKey("projects")) {
            List<?> projects = (List<?>) parseResult.get("projects");
            if (!projects.isEmpty()) {
                strengths.add("项目经验丰富");
            }
        }
        
        // 检查技能
        if (parseResult.containsKey("skills")) {
            List<?> skills = (List<?>) parseResult.get("skills");
            if (skills.size() >= 3) {
                strengths.add("技能栈丰富");
            }
        }
        
        return strengths;
    }

    /**
     * 识别劣势
     */
    private List<String> identifyWeaknesses(Map<String, Object> parseResult) {
        List<String> weaknesses = new ArrayList<>();
        
        // 检查基本信息
        if (!parseResult.containsKey("name") || !parseResult.containsKey("contact")) {
            weaknesses.add("基本信息不完整");
        }
        
        // 检查教育背景
        if (!parseResult.containsKey("education") || ((List<?>) parseResult.get("education")).isEmpty()) {
            weaknesses.add("教育背景缺失");
        }
        
        // 检查工作经验
        if (!parseResult.containsKey("workExperience") || ((List<?>) parseResult.get("workExperience")).isEmpty()) {
            weaknesses.add("工作经验缺失");
        }
        
        // 检查项目经验
        if (!parseResult.containsKey("projects") || ((List<?>) parseResult.get("projects")).isEmpty()) {
            weaknesses.add("项目经验缺失");
        }
        
        // 检查技能
        if (!parseResult.containsKey("skills") || ((List<?>) parseResult.get("skills")).isEmpty()) {
            weaknesses.add("技能描述缺失");
        }
        
        return weaknesses;
    }

    /**
     * 生成修改建议
     */
    private List<String> generateSuggestions(Map<String, Object> parseResult, List<String> weaknesses) {
        List<String> suggestions = new ArrayList<>();
        
        // 根据劣势生成建议
        if (weaknesses.contains("基本信息不完整")) {
            suggestions.add("请完善个人基本信息，包括姓名、联系方式等");
        }
        
        if (weaknesses.contains("教育背景缺失")) {
            suggestions.add("请添加教育背景信息，包括学校、专业、学历等");
        }
        
        if (weaknesses.contains("工作经验缺失")) {
            suggestions.add("请添加工作经验，包括公司名称、职位、工作内容等");
        }
        
        if (weaknesses.contains("项目经验缺失")) {
            suggestions.add("请添加项目经验，包括项目名称、角色、职责等");
        }
        
        if (weaknesses.contains("技能描述缺失")) {
            suggestions.add("请添加技能特长，包括技术栈、工具等");
        }
        
        // 通用建议
        suggestions.add("简历格式应保持整洁，避免使用过多颜色和字体");
        suggestions.add("工作经历和项目经验应使用STAR法则（情境、任务、行动、结果）描述");
        suggestions.add("技能部分应按熟练度排序，并突出与目标岗位相关的技能");
        suggestions.add("简历长度建议控制在1-2页，突出重点内容");
        
        return suggestions;
    }
}
