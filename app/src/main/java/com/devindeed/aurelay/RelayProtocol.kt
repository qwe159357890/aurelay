package com.devindeed.aurelay

import java.io.ByteArrayOutputStream

/**
 * 中转协议常量与帧编解码（第 9 章）
 *
 * 通用帧：`ARLY`（4 字节魔数）+ 类型（1 字节）+ 载荷长度（4 字节大端）+ 载荷
 *
 * 设计要点：中转服务器**不解析音频内容**，`DATA` 帧的载荷是原样透传的 AURL 字节流，
 * 因此客户端侧不需要理解音频语义，只负责把收到的 DATA 载荷拼成一条连续的流。
 */
object RelayProtocol {

    // 帧魔数
    val MAGIC: ByteArray = byteArrayOf(0x41, 0x52, 0x4C, 0x59) // "ARLY"

    // 魔数长度
    const val MAGIC_SIZE = 4

    // 帧头总长度（魔数 + 类型 + 长度）
    const val HEADER_SIZE = 9

    // 协议版本
    const val VERSION: Byte = 1

    // 消息类型
    const val TYPE_HELLO: Byte = 0x01
    const val TYPE_READY: Byte = 0x02
    const val TYPE_ERROR: Byte = 0x03
    const val TYPE_PING: Byte = 0x04
    const val TYPE_PONG: Byte = 0x05
    const val TYPE_DATA: Byte = 0x06

    // 角色：接收方（手机放音时是手机）
    const val ROLE_RECEIVER: Byte = 0x52 // 'R'

    // 角色：发送方（手机放音时是 PC）
    const val ROLE_SENDER: Byte = 0x53 // 'S'

    // 默认中转端口
    const val DEFAULT_PORT = 15151

    // 心跳间隔（毫秒）
    const val PING_INTERVAL_MS = 30_000L

    // 重连退避档位（毫秒），封顶 30 秒
    val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

    /**
     * 构造一个完整帧（魔数 + 类型 + 长度 + 载荷）
     *
     * :param type: 消息类型，取自 TYPE_* 常量
     * :param payload: 载荷字节；无载荷时传空数组
     * :return: 可直接写入 socket 的完整帧字节
     */
    fun frame(type: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val buffer = ByteArrayOutputStream(MAGIC_SIZE + 5 + payload.size)
        buffer.write(MAGIC, 0, MAGIC_SIZE)
        buffer.write(type.toInt())
        writeInt32(buffer, payload.size)
        buffer.write(payload, 0, payload.size)
        return buffer.toByteArray()
    }

    /**
     * 构造 HELLO 载荷
     *
     * 载荷布局：`ver(1) + role(1) + device_id(1字节长度+utf8) + token(1字节长度+utf8) + port(2)`
     *
     * :param role: 角色，ROLE_RECEIVER 或 ROLE_SENDER
     * :param deviceId: 设备标识（与地址上报用同一个）
     * :param token: 上报令牌（与 HTTP API 同一把）
     * :param port: 本机音频端口（接收方填 5000，发送方填 0）
     * :return: HELLO 载荷字节
     */
    fun helloPayload(role: Byte, deviceId: String, token: String, port: Int): ByteArray {
        val idBytes = deviceId.toByteArray(Charsets.UTF_8)
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        val buffer = ByteArrayOutputStream()
        buffer.write(VERSION.toInt())
        buffer.write(role.toInt())
        buffer.write(idBytes.size and 0xFF)
        buffer.write(idBytes, 0, idBytes.size)
        buffer.write(tokenBytes.size and 0xFF)
        buffer.write(tokenBytes, 0, tokenBytes.size)
        writeInt16(buffer, port)
        return buffer.toByteArray()
    }

    /**
     * 以大端顺序写入 4 字节整数
     *
     * :param buffer: 输出流
     * :param value: 待写入的整数
     * :return: 无返回值
     */
    private fun writeInt32(buffer: ByteArrayOutputStream, value: Int) {
        buffer.write((value ushr 24) and 0xFF)
        buffer.write((value ushr 16) and 0xFF)
        buffer.write((value ushr 8) and 0xFF)
        buffer.write(value and 0xFF)
    }

    /**
     * 以大端顺序写入 2 字节整数
     *
     * :param buffer: 输出流
     * :param value: 待写入的整数
     * :return: 无返回值
     */
    private fun writeInt16(buffer: ByteArrayOutputStream, value: Int) {
        buffer.write((value ushr 8) and 0xFF)
        buffer.write(value and 0xFF)
    }

    /**
     * 从缓冲区读取大端 4 字节整数
     *
     * :param data: 数据源
     * :param offset: 起始下标
     * :return: 解析出的整数
     */
    fun readInt32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    /**
     * 解析 ERROR 帧载荷中的错误码
     *
     * :param payload: ERROR 帧载荷
     * :return: 错误码；载荷过短时返回 -1
     */
    fun errorCode(payload: ByteArray): Int {
        if (payload.size < 2) return -1
        return ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
    }

    /**
     * 解析 ERROR 帧载荷中的错误信息
     *
     * :param payload: ERROR 帧载荷
     * :return: 错误信息文本；无信息时返回空串
     */
    fun errorMessage(payload: ByteArray): String {
        if (payload.size < 3) return ""
        val length = payload[2].toInt() and 0xFF
        val end = (3 + length).coerceAtMost(payload.size)
        return String(payload, 3, end - 3, Charsets.UTF_8)
    }
}
