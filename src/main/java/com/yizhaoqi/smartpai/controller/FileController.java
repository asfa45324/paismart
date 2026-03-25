package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件分析控制器
 */
@RestController
@RequestMapping("/api/v1/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * 分析文件
     * @param fileMd5 文件MD5
     * @return 分析结果
     */
    @GetMapping("/analyze")
    public ResponseEntity<?> analyzeFile(@RequestParam("file_md5") String fileMd5) {
        Map<String, Object> analysisResult = fileService.analyzeFile(fileMd5);
        
        // 构建符合前端期望格式的响应
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "分析成功");
        response.put("data", analysisResult);
        
        return ResponseEntity.ok(response);
    }
}
