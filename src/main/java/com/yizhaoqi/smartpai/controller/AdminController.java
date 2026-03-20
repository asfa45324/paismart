package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @Autowired
    private ConversationRepository conversationRepository;
    
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    /**
     * 管理员查询聊天记录
     */
    @GetMapping("/conversation")
    public ResponseEntity<?> getConversations(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) Long userid,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("ADMIN_GET_CONVERSATIONS");
        String username = null;
        try {
            // 从token中提取用户名
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "ADMIN_GET_CONVERSATIONS", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("获取对话历史失败：无效token");
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }
            
            // 验证用户是否为管理员
            User admin = userRepository.findByUsername(username)
                    .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
            
            if (admin.getRole() != User.Role.ADMIN) {
                LogUtils.logUserOperation(username, "ADMIN_GET_CONVERSATIONS", "authorization", "FAILED_NOT_ADMIN");
                monitor.end("获取对话历史失败：非管理员");
                throw new CustomException("权限不足", HttpStatus.FORBIDDEN);
            }
            
            LogUtils.logBusiness("ADMIN_GET_CONVERSATIONS", username, "开始查询管理员对话历史");
            
            // 解析时间范围
            LocalDateTime startDateTime = null;
            LocalDateTime endDateTime = null;
            
            if (start_date != null && !start_date.trim().isEmpty()) {
                try {
                    startDateTime = parseDateTime(start_date);
                    LogUtils.logBusiness("ADMIN_GET_CONVERSATIONS", username, "解析起始时间: %s -> %s", start_date, startDateTime);
                } catch (Exception e) {
                    LogUtils.logBusinessError("ADMIN_GET_CONVERSATIONS", username, "起始时间解析失败: %s", e, start_date);
                    throw new CustomException("起始时间格式错误: " + start_date, HttpStatus.BAD_REQUEST);
                }
            }
            
            if (end_date != null && !end_date.trim().isEmpty()) {
                try {
                    endDateTime = parseDateTime(end_date);
                    LogUtils.logBusiness("ADMIN_GET_CONVERSATIONS", username, "解析结束时间: %s -> %s", end_date, endDateTime);
                } catch (Exception e) {
                    LogUtils.logBusinessError("ADMIN_GET_CONVERSATIONS", username, "结束时间解析失败: %s", e, end_date);
                    throw new CustomException("结束时间格式错误: " + end_date, HttpStatus.BAD_REQUEST);
                }
            }
            
            // 调用服务获取聊天记录
            List<Conversation> conversations;
            if (userid != null) {
                // 特殊处理：当选择的是管理员用户（ID=1）时，查询所有用户的聊天记录
                if (userid == 1L) {
                    // 查询所有用户的聊天记录
                    if (startDateTime != null && endDateTime != null) {
                        conversations = conversationRepository.findByTimestampBetween(startDateTime, endDateTime);
                    } else {
                        conversations = conversationRepository.findAll();
                    }
                } else {
                    // 查询指定用户的聊天记录
                    User targetUser = userRepository.findById(userid)
                            .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
                    if (startDateTime != null && endDateTime != null) {
                        conversations = conversationRepository.findByUserIdAndTimestampBetween(
                                userid, startDateTime, endDateTime);
                    } else {
                        conversations = conversationRepository.findByUserId(userid);
                    }
                }
            } else {
                // 查询所有用户的聊天记录
                if (startDateTime != null && endDateTime != null) {
                    conversations = conversationRepository.findByTimestampBetween(startDateTime, endDateTime);
                } else {
                    conversations = conversationRepository.findAll();
                }
            }
            
            // 转换为前端需要的格式
            List<Map<String, Object>> formattedConversations = new ArrayList<>();
            for (Conversation conversation : conversations) {
                // 添加用户消息
                Map<String, Object> userMessage = new HashMap<>();
                userMessage.put("role", "user");
                userMessage.put("content", conversation.getQuestion());
                userMessage.put("timestamp", conversation.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
                formattedConversations.add(userMessage);
                
                // 添加助手消息
                Map<String, Object> assistantMessage = new HashMap<>();
                assistantMessage.put("role", "assistant");
                assistantMessage.put("content", conversation.getAnswer());
                assistantMessage.put("timestamp", conversation.getTimestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
                formattedConversations.add(assistantMessage);
            }
            
            LogUtils.logBusiness("ADMIN_GET_CONVERSATIONS", username, "获取到 %d 条对话记录", formattedConversations.size());
            LogUtils.logUserOperation(username, "ADMIN_GET_CONVERSATIONS", "conversation_history", "SUCCESS");
            monitor.end("获取对话历史成功");
            
            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取对话历史成功");
            response.put("data", formattedConversations);
            return ResponseEntity.ok().body(response);
            
        } catch (CustomException e) {
            LogUtils.logBusinessError("ADMIN_GET_CONVERSATIONS", username, "获取对话历史失败: %s", e, e.getMessage());
            monitor.end("获取对话历史失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("ADMIN_GET_CONVERSATIONS", username, "获取对话历史异常: %s", e, e.getMessage());
            monitor.end("获取对话历史异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

    /**
     * 解析日期时间字符串，支持多种格式
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }
        
        try {
            // 尝试标准格式解析 (2023-01-01T12:00:00)
            return LocalDateTime.parse(dateTimeStr);
        } catch (Exception e1) {
            try {
                // 尝试解析日期格式 (2023-01-01)
                if (dateTimeStr.length() == 10) {
                    return LocalDateTime.parse(dateTimeStr + "T00:00:00");
                }
                
                // 尝试使用自定义格式解析
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
                return LocalDateTime.parse(dateTimeStr, formatter);
            } catch (Exception e2) {
                LogUtils.logBusinessError("PARSE_DATETIME", "system", "无法解析日期时间: %s", e2, dateTimeStr);
                throw new CustomException("无效的日期格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
            }
        }
    }

    /**
     * 管理员查询用户列表
     */
    @GetMapping("/users/list")
    public ResponseEntity<?> getUsers(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size,
            @RequestParam(required = false) String orgTag) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("ADMIN_GET_USERS");
        String username = null;
        try {
            // 从token中提取用户名
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "ADMIN_GET_USERS", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("获取用户列表失败：无效token");
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }
            
            // 验证用户是否为管理员
            User admin = userRepository.findByUsername(username)
                    .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
            
            if (admin.getRole() != User.Role.ADMIN) {
                LogUtils.logUserOperation(username, "ADMIN_GET_USERS", "authorization", "FAILED_NOT_ADMIN");
                monitor.end("获取用户列表失败：非管理员");
                throw new CustomException("权限不足", HttpStatus.FORBIDDEN);
            }
            
            LogUtils.logBusiness("ADMIN_GET_USERS", username, "开始查询用户列表, page: %d, size: %d, orgTag: %s", page, size, orgTag);
            
            // 查询用户列表
            List<User> users = userRepository.findAll();
            
            // 转换为前端需要的格式
            List<Map<String, Object>> formattedUsers = new ArrayList<>();
            for (User user : users) {
                Map<String, Object> userMap = new HashMap<>();
                userMap.put("userId", user.getId());
                userMap.put("username", user.getUsername());
                userMap.put("primaryOrg", user.getPrimaryOrg());
                userMap.put("orgTags", user.getOrgTags());
                userMap.put("role", user.getRole());
                userMap.put("createdAt", user.getCreatedAt());
                formattedUsers.add(userMap);
            }
            
            LogUtils.logBusiness("ADMIN_GET_USERS", username, "获取到 %d 条用户记录", formattedUsers.size());
            LogUtils.logUserOperation(username, "ADMIN_GET_USERS", "user_list", "SUCCESS");
            monitor.end("获取用户列表成功");
            
            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取用户列表成功");
            response.put("data", formattedUsers);
            return ResponseEntity.ok().body(response);
            
        } catch (CustomException e) {
            LogUtils.logBusinessError("ADMIN_GET_USERS", username, "获取用户列表失败: %s", e, e.getMessage());
            monitor.end("获取用户列表失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("ADMIN_GET_USERS", username, "获取用户列表异常: %s", e, e.getMessage());
            monitor.end("获取用户列表异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }
}

