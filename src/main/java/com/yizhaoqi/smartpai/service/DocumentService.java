package com.yizhaoqi.smartpai.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.OSSObject;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档管理服务类
 * 负责文档的删除等管理操作
 */
@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    // 用于与阿里云 OSS 交互
    @Autowired
    private OSS ossClient;

    @Autowired
    private String ossBucketName;

    // 存储服务类型：oss 或 minio
    private final String storageService = "oss"; // 默认使用阿里云 OSS

    /**
     * 删除文档及其相关数据
     * 该方法将删除:
     * 1. FileUpload记录
     * 2. DocumentVector记录
     * 3. 存储服务中的文件
     * 4. Elasticsearch中的向量数据
     *
     * @param fileMd5 文件MD5
     */
    @Transactional
    public void deleteDocument(String fileMd5, String userId) {
        logger.info("开始删除文档: {}", fileMd5);

        try {
            // 获取文件信息以获取文件名
            FileUpload fileUpload = fileUploadRepository.findByFileMd5AndUserId(fileMd5, userId)
                    .orElseThrow(() -> new RuntimeException("文件不存在"));

            // 1. 删除Elasticsearch中的数据
            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
                logger.info("成功从Elasticsearch删除文档: {}", fileMd5);
            } catch (Exception e) {
                logger.error("从Elasticsearch删除文档时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }

            // 2. 删除存储服务中的文件
            try {
                String objectName = "merged/" + fileUpload.getFileName();
                if ("oss".equals(storageService)) {
                    // 从OSS删除文件
                    ossClient.deleteObject(ossBucketName, objectName);
                    logger.info("成功从OSS删除文件: {}", objectName);
                } else {
                    // 从MinIO删除文件
                    minioClient.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket("uploads")
                                    .object(objectName)
                                    .build());
                    logger.info("成功从MinIO删除文件: {}", objectName);
                }
            } catch (Exception e) {
                logger.error("从存储服务删除文件时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }

            // 3. 删除DocumentVector记录
            try {
                documentVectorRepository.deleteByFileMd5(fileMd5);
                logger.info("成功删除文档向量记录: {}", fileMd5);
            } catch (Exception e) {
                logger.error("删除文档向量记录时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }

            // 4. 删除FileUpload记录
            fileUploadRepository.deleteByFileMd5(fileMd5);
            logger.info("成功删除文件上传记录: {}", fileMd5);

            logger.info("文档删除完成: {}", fileMd5);
        } catch (Exception e) {
            logger.error("删除文档过程中发生错误: {}", fileMd5, e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取用户可访问的所有文件列表
     * 包括用户自己的文件、公开文件和用户所属组织的文件（支持层级权限）
     *
     * @param userId  用户ID
     * @param orgTags 用户所属的组织标签（逗号分隔的字符串，仅供兼容性使用）
     * @return 用户可访问的文件列表
     */
    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        logger.info("获取用户可访问文件列表: userId={}", userId);

        try {
            // 获取用户有效的组织标签（包含层级关系）
            User user;
            try {
                // 尝试将userId解析为Long（用户ID）
                Long userIdLong = Long.parseLong(userId);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
            } catch (NumberFormatException e) {
                // 如果userId不是数字，则作为用户名查找
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
            }

            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户有效组织标签: {}", userEffectiveTags);

            // 使用用户ID进行查询
            String userDbId = user.getId().toString();

            // 使用有效标签查询文件
            List<FileUpload> files;
            if (userEffectiveTags.isEmpty()) {
                // 如果用户没有任何组织标签，只返回自己的文件和公开文件
                files = fileUploadRepository.findByUserIdOrIsPublicTrue(userDbId);
                logger.debug("用户无组织标签，仅返回个人和公开文件");
            } else {
                // 查询用户可访问的所有文件（考虑层级标签）
                files = fileUploadRepository.findAccessibleFilesWithTags(userDbId, userEffectiveTags);
                logger.debug("使用有效组织标签查询文件");
            }

            logger.info("成功获取用户可访问文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户可访问文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取可访问文件列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取用户上传的所有文件列表
     *
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    public List<FileUpload> getUserUploadedFiles(String userId) {
        logger.info("获取用户上传的文件列表: userId={}", userId);

        try {
            List<FileUpload> files = fileUploadRepository.findByUserId(userId);
            logger.info("成功获取用户上传的文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户上传的文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取用户上传的文件列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成文件下载链接
     * 
     * @param fileMd5 文件MD5
     * @return 预签名下载URL
     */
    public String generateDownloadUrl(String fileMd5) {
        logger.info("生成文件下载链接: fileMd5={}", fileMd5);

        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 对象路径格式: merged/文件名
            String objectName = "merged/" + fileUpload.getFileName();

            String downloadUrl;
            if ("oss".equals(storageService)) {
                // 从OSS生成预签名URL，有效期1小时
                java.util.Date expiration = new java.util.Date();
                long expTimeMillis = expiration.getTime();
                expTimeMillis += 1000 * 60 * 60; // 1小时
                expiration.setTime(expTimeMillis);
                // 直接使用 bucket 名称、对象名称和过期时间生成预签名URL
                downloadUrl = ossClient.generatePresignedUrl(ossBucketName, objectName, expiration).toString();
                logger.info("成功生成OSS文件下载链接: fileMd5={}, fileName={}, objectName={}, url={}",
                        fileMd5, fileUpload.getFileName(), objectName, downloadUrl);
            } else {
                // 从MinIO生成预签名URL，有效期1小时
                String presignedUrl = minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket("uploads")
                                .object(objectName)
                                .expiry(3600) // 1小时有效期
                                .build());
                downloadUrl = presignedUrl;
                logger.info("成功生成MinIO文件下载链接: fileMd5={}, fileName={}, objectName={}",
                        fileMd5, fileUpload.getFileName(), objectName);
            }

            return downloadUrl;
        } catch (Exception e) {
            logger.error("生成文件下载链接失败: fileMd5={}", fileMd5, e);
            return null;
        }
    }

    /**
     * 生成文件预览链接（用于在浏览器中直接预览，如PDF）
     * 
     * @param fileMd5 文件MD5
     * @return 预签名预览URL
     */
    public String generatePreviewUrl(String fileMd5) {
        logger.info("生成文件预览链接: fileMd5={}", fileMd5);

        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 对象路径格式: merged/文件名
            String objectName = "merged/" + fileUpload.getFileName();

            String previewUrl;
            if ("oss".equals(storageService)) {
                // 从OSS生成预签名URL，有效期1小时
                // 对于OSS，我们需要确保返回的URL不会触发下载
                java.util.Date expiration = new java.util.Date();
                long expTimeMillis = expiration.getTime();
                expTimeMillis += 1000 * 60 * 60; // 1小时
                expiration.setTime(expTimeMillis);
                // 直接使用 bucket 名称、对象名称和过期时间生成预签名URL
                previewUrl = ossClient.generatePresignedUrl(ossBucketName, objectName, expiration).toString();
                logger.info("成功生成OSS文件预览链接: fileMd5={}, fileName={}, objectName={}, url={}",
                        fileMd5, fileUpload.getFileName(), objectName, previewUrl);
            } else {
                // 从MinIO生成预签名URL，有效期1小时
                String presignedUrl = minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket("uploads")
                                .object(objectName)
                                .expiry(3600) // 1小时有效期
                                .build());
                previewUrl = presignedUrl;
                logger.info("成功生成MinIO文件预览链接: fileMd5={}, fileName={}, objectName={}",
                        fileMd5, fileUpload.getFileName(), objectName);
            }

            return previewUrl;
        } catch (Exception e) {
            logger.error("生成文件预览链接失败: fileMd5={}", fileMd5, e);
            return null;
        }
    }

    /**
     * 获取文件预览内容
     * 
     * @param fileMd5  文件MD5
     * @param fileName 文件名
     * @return 文件预览内容，对于文本文件返回前几KB内容，非文本文件返回文件信息
     */
    public String getFilePreviewContent(String fileMd5, String fileName) {
        logger.info("获取文件预览内容: fileMd5={}, fileName={}", fileMd5, fileName);

        try {
            // 对象路径格式: merged/文件名
            String objectName = "merged/" + fileName;

            // 判断文件类型
            String fileExtension = getFileExtension(fileName).toLowerCase();
            boolean isTextFile = isTextFile(fileExtension);

            if (isTextFile) {
                // 对于文本文件，读取前10KB内容
                try (InputStream inputStream = getFileInputStream(objectName)) {

                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
                    StringBuilder content = new StringBuilder();
                    String line;
                    int bytesRead = 0;
                    int maxBytes = 10240; // 10KB

                    while ((line = reader.readLine()) != null && bytesRead < maxBytes) {
                        content.append(line).append("\n");
                        bytesRead += line.getBytes("UTF-8").length + 1;
                    }

                    String result = content.toString();
                    if (bytesRead >= maxBytes) {
                        result += "\n... (内容已截断，仅显示前10KB)";
                    }

                    logger.info("成功获取文本文件预览内容: fileMd5={}, contentLength={}", fileMd5, result.length());
                    return result;
                }
            } else {
                // 对于非文本文件，返回文件信息
                FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                        .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

                String fileInfo = String.format(
                        "文件名: %s\n" +
                                "文件大小: %s\n" +
                                "文件类型: %s\n" +
                                "上传时间: %s\n\n" +
                                "此文件类型不支持预览，请下载后查看。",
                        fileName,
                        formatFileSize(fileUpload.getTotalSize()),
                        fileExtension.toUpperCase(),
                        fileUpload.getCreatedAt());

                logger.info("返回非文本文件信息: fileMd5={}", fileMd5);
                return fileInfo;
            }

        } catch (Exception e) {
            logger.error("获取文件预览内容失败: fileMd5={}, fileName={}", fileMd5, fileName, e);
            return "预览失败: " + e.getMessage();
        }
    }

    /**
     * 获取文件输入流，根据存储服务类型选择从OSS或MinIO获取
     */
    private InputStream getFileInputStream(String objectName) throws Exception {
        if ("oss".equals(storageService)) {
            // 从OSS获取文件输入流
            OSSObject ossObject = ossClient.getObject(ossBucketName, objectName);
            return ossObject.getObjectContent();
        } else {
            // 从MinIO获取文件输入流
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket("uploads")
                            .object(objectName)
                            .build());
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }

    /**
     * 判断是否为文本文件
     */
    private boolean isTextFile(String extension) {
        String[] textExtensions = {
                "txt", "md", "doc", "docx", "pdf", "html", "htm", "xml", "json",
                "csv", "log", "java", "js", "ts", "py", "cpp", "c", "h", "css",
                "scss", "less", "sql", "yml", "yaml", "properties", "conf", "config"
        };

        return Arrays.stream(textExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(Long size) {
        if (size == null)
            return "未知";

        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 获取文件内容
     * 
     * @param fileMd5 文件MD5
     * @return 文件内容
     */
    public String getFileContent(String fileMd5) {
        logger.info("获取文件内容: fileMd5={}", fileMd5);

        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findByFileMd5(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 对象路径格式: merged/文件名
            String objectName = "merged/" + fileUpload.getFileName();

            // 读取文件内容
            try (InputStream inputStream = getFileInputStream(objectName)) {

                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
                String content = reader.lines().collect(Collectors.joining("\n"));

                logger.info("成功获取文件内容: fileMd5={}, contentLength={}", fileMd5, content.length());
                return content;
            }

        } catch (Exception e) {
            logger.error("获取文件内容失败: fileMd5={}", fileMd5, e);
            throw new RuntimeException("获取文件内容失败: " + e.getMessage(), e);
        }
    }
}