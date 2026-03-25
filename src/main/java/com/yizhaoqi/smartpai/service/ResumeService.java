package com.yizhaoqi.smartpai.service;

import java.util.Map;

/**
 * 简历服务接口
 */
public interface ResumeService {

    /**
     * 分析简历
     * @param fileMd5 文件MD5
     * @return 分析结果
     */
    Map<String, Object> analyzeResume(String fileMd5);

    /**
     * 解析简历
     * @param fileMd5 文件MD5
     * @return 解析结果
     */
    Map<String, Object> parseResume(String fileMd5);
}
