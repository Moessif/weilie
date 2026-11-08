package com.gakj.weilie.ui.team

import android.Manifest
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.gakj.weilie.ui.common.GlassIconButton
import com.gakj.weilie.ui.common.hasPerm
import com.gakj.weilie.ui.common.vibrate
import com.gakj.weilie.ui.theme.WColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * 扫一扫（STRICT_SPEC §3.5）：CameraX 取景 + ZXing 本地解码，
 * 识别到「WEILIE:房号」即回调；无网络、无云端识别。
 */
@Composable
fun ScanScreen(onBack: () -> Unit, onScanned: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasPerm(ctx, Manifest.permission.CAMERA)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(WColors.Bg0),
    ) {
        if (granted) {
            CameraPreviewArea(onScanned = onScanned)
            // 取景框
            Canvas(
                Modifier
                    .align(Alignment.Center)
                    .size(252.dp),
            ) {
                val c = WColors.Primary
                val len = size.minDimension * 0.16f
                val st = Stroke(width = 4.dp.toPx())
                // 四个角的 L 形角标
                drawLines(listOf(Offset(0f, 0f), Offset(len, 0f)), c, st)
                drawLines(listOf(Offset(0f, 0f), Offset(0f, len)), c, st)
                drawLines(listOf(Offset(size.width, 0f), Offset(size.width - len, 0f)), c, st)
                drawLines(listOf(Offset(size.width, 0f), Offset(size.width, len)), c, st)
                drawLines(listOf(Offset(0f, size.height), Offset(len, size.height)), c, st)
                drawLines(listOf(Offset(0f, size.height), Offset(0f, size.height - len)), c, st)
                drawLines(listOf(Offset(size.width, size.height), Offset(size.width - len, size.height)), c, st)
                drawLines(listOf(Offset(size.width, size.height), Offset(size.width, size.height - len)), c, st)
            }
            Text(
                "对准队伍二维码（WEILIE:房号）",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 48.dp)
                    .background(Color(0x880B1020), RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "需要相机权限才能扫码",
                    style = MaterialTheme.typography.titleMedium,
                    color = WColors.OnBg,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "仅用于本地识别队伍二维码，不会上传任何画面",
                    style = MaterialTheme.typography.bodySmall,
                    color = WColors.OnBgDim,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = WColors.Primary, contentColor = Color(0xFF052E33)),
                ) { Text("授予权限") }
            }
        }

        // 左上返回
        Row(
            Modifier
                .statusBarsPadding()
                .padding(10.dp),
        ) {
            GlassIconButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, size = 44.dp) { onBack() }
        }
    }
}

@Composable
private fun CameraPreviewArea(onScanned: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    // QA-11：无相机/无权限/绑定失败时不能只留一块纯黑屏，要给出可操作的提示
    var cameraError by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewCtx ->
                val pv = PreviewView(viewCtx)
                val future = ProcessCameraProvider.getInstance(viewCtx)
                future.addListener({
                    runCatching {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = pv.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            android.util.Size(640, 480),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                        ),
                                    )
                                    .build(),
                            )
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(executor, QrAnalyzer { code ->
                                    main.post {
                                        vibrate(viewCtx, 80)
                                        onScanned(code)
                                    }
                                })
                            }
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { e ->
                        main.post {
                            cameraError = if (!hasPerm(ctx, Manifest.permission.CAMERA)) {
                                "没有相机权限，无法扫码"
                            } else {
                                "无法启动相机（${e.message ?: e.javaClass.simpleName}）"
                            }
                        }
                    }
                }, ContextCompat.getMainExecutor(viewCtx))
                pv
            },
        )

        cameraError?.let { msg ->
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xCC05070D)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(msg, style = MaterialTheme.typography.titleMedium, color = WColors.OnBg)
                Spacer(Modifier.height(10.dp))
                Text(
                    "也可以让队友把队伍码念给你，返回上一页手动输入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WColors.OnBgDim,
                )
            }
        }
    }
}

/** ZXing 逐帧解码：Y 平面亮度 + 旋转 90° 重试一次（相机竖屏场景） */
private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    private val reader = MultiFormatReader()
    private var done = false

    override fun analyze(image: ImageProxy) {
        if (done) {
            image.close()
            return
        }
        val text = try {
            val yPlane = image.planes[0]
            val buffer = yPlane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val w = image.width
            val h = image.height
            val rowStride = yPlane.rowStride
            // 先按原方向解
            decode(PlanarYUVLuminanceSource(bytes, rowStride, h, 0, 0, w, h, false))
                // 竖屏相机帧常带 90° 旋转，旋转后重试
                ?: decode(rotatedSource(bytes, rowStride, w, h))
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
        val clean = text?.trim().orEmpty()
        if (clean.startsWith("WEILIE:") && clean.length > 7) {
            done = true
            onResult(clean.removePrefix("WEILIE:").take(8))
        }
    }

    private fun decode(source: PlanarYUVLuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    /** 亮度矩阵顺时针旋转 90°（w/h 互换），用于竖屏帧 */
    private fun rotatedSource(bytes: ByteArray, rowStride: Int, w: Int, h: Int): PlanarYUVLuminanceSource {
        val out = ByteArray(w * h)
        for (row in 0 until h) {
            val base = row * rowStride
            for (col in 0 until w) {
                out[col * h + (h - 1 - row)] = bytes[base + col]
            }
        }
        return PlanarYUVLuminanceSource(out, h, w, 0, 0, h, w, false)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLines(points: List<Offset>, color: Color, st: Stroke) {
    for (i in 0 until points.size - 1) {
        drawLine(color, points[i], points[i + 1], st.width)
    }
}
