package com.lego.harbor.ws.backend;

/**
 * Khoá của 1 trong N link song song (shard) mà gateway giữ tới 1 pod colony — dùng chung cho MỌI
 * client session trên node harbor này (thay vì 1 link/session như trước, xem
 * {@link BackendLinkGateway#shardFor}).
 */
record PodShardKey(String podName, int shardIndex) {}
