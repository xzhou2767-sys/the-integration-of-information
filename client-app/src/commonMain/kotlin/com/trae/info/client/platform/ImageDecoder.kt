package com.trae.info.client.platform

import androidx.compose.ui.graphics.ImageBitmap

/** 跨平台图片解码（云端登录页/二维码截图为 PNG 字节） */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
