package com.yizhaoqi.smartpai.service;

import java.util.Map;

/**
 * 文件服务接口
 */
public interface FileService {

    /**
     * 分析文件
     * @param fileMd5 文件MD5
     * @return 分析结果
     */
    Map<String, Object> analyzeFile(String fileMd5);
}
