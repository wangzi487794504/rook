---
title: "task"
author: wangzijie
date: 2026-08-05
---

# MewCode 第6章 - MCP 客户端 Tasks

## T1: JsonRpc 消息工具
**文件:** `mcp/JsonRpc.java`
**步骤:** JSON-RPC 2.0 请求构造和响应解析, 带 AtomicInteger 生成 id

## T2: McpServerConfig + McpConfig
**文件:** `mcp/McpConfig.java`
**步骤:** record McpServerConfig, YAML加载+两层合并+${VAR}展开

## T3: McpTransport 接口 + StdioTransport
**文件:** `mcp/McpTransport.java`, `mcp/StdioTransport.java`
**步骤:** 接口(send/close), ProcessBuilder实现

## T4: HttpTransport
**文件:** `mcp/HttpTransport.java`
**步骤:** HttpClient POST + SSE响应解析

## T5: McpSession
**文件:** `mcp/McpSession.java`
**步骤:** 三阶段(initialize/listTools/callTool), 内部BlockingQueue串行+

## T6: McpToolAdapter
**文件:** `mcp/McpToolAdapter.java`
**步骤:** 包装 McpSession + toolDef 为 Tool 接口

## T7: McpManager
**文件:** `mcp/McpManager.java`
**步骤:** 加载配置→并行初始化→发现工具→注册到ToolRegistry

## T8: App 集成
**文件:** `core/App.java`
**步骤:** 创建 McpManager, discover() 注册工具

## T9-T11: 测试
**文件:** JsonRpcTest / McpConfigTest / McpToolAdapterTest
