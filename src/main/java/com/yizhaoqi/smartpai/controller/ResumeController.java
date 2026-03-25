package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.ResumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 简历分析控制器
 */
@RestController
@RequestMapping("/api/v1/resume")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    /**
     * 分析简历
     * @param fileMd5 文件MD5
     * @return 分析结果
     */
    @GetMapping("/analyze")
    public ResponseEntity<?> analyzeResume(@RequestParam("file_md5") String fileMd5) {
        Map<String, Object> analysisResult = resumeService.analyzeResume(fileMd5);
        return ResponseEntity.ok(analysisResult);
    }

    /**
     * 获取简历解析结果
     * @param fileMd5 文件MD5
     * @return 解析结果
     */
    @GetMapping("/parse")
    public ResponseEntity<?> parseResume(@RequestParam("file_md5") String fileMd5) {
        Map<String, Object> parseResult = resumeService.parseResume(fileMd5);
        return ResponseEntity.ok(parseResult);
    }
}
