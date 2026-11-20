package com.mob.lee.fastair.io

interface Writer : Iterator<ProtocolByte> {
    /**
     * 该 Writer 不会再被消费时调用，用于释放它持有的资源（文件句柄等）。
     *
     * 连接断开、写失败、任务被丢弃时都会触发，实现必须可重复调用。
     */
    fun cancel(cause: Exception) {}
}
