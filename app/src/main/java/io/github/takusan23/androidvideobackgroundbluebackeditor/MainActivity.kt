package io.github.takusan23.androidvideobackgroundbluebackeditor

import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.contentValuesOf
import io.github.takusan23.akaricore.common.toAkariCoreInputOutputData
import io.github.takusan23.akaricore.video.CanvasVideoProcessor
import io.github.takusan23.akaricore.video.VideoFrameBitmapExtractor
import io.github.takusan23.androidvideobackgroundbluebackeditor.ui.theme.AndroidVideoBackgroundTransparentEditorTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidVideoBackgroundTransparentEditorTheme {
                ImageSegmentationScreen()
            }
        }
    }
}

@Composable
private fun ImageSegmentationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaPipeImageSegmentation = remember { MediaPipeImageSegmentation(context) }

    val encodedPositionMs = remember { mutableLongStateOf(0) }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            // 選んでもらったら処理開始
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                // 動画サイズが欲しい
                val metadataRetriever = MediaMetadataRetriever().apply {
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { setDataSource(it.fileDescriptor) }
                }
                val videoWidth = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                val videoHeight = metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()

                // 一枚一枚取り出すやつ。MetadataRetriever より速い
                val videoFrameBitmapExtractor = VideoFrameBitmapExtractor()
                videoFrameBitmapExtractor.prepareDecoder(uri.toAkariCoreInputOutputData(context))

                // BB 素材保存先
                val resultVideoMetadata = contentValuesOf(
                    MediaStore.Video.VideoColumns.DISPLAY_NAME to "${System.currentTimeMillis()}.mp4",
                    MediaStore.Video.VideoColumns.MIME_TYPE to "video/mp4",
                    MediaStore.MediaColumns.RELATIVE_PATH to "${Environment.DIRECTORY_MOVIES}/AndroidVideoBackgroundBlueBackEditor"
                )
                val resultVideoFileUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, resultVideoMetadata)!!

                // Canvas から動画を作るやつ
                val paint = Paint()
                CanvasVideoProcessor.start(
                    output = resultVideoFileUri.toAkariCoreInputOutputData(context),
                    outputVideoWidth = videoWidth,
                    outputVideoHeight = videoHeight,
                    onCanvasDrawRequest = { positionMs ->
                        // 一枚一枚取り出す
                        val videoFrameBitmap = videoFrameBitmapExtractor.getVideoFrameBitmap(positionMs)
                        if (videoFrameBitmap != null) {
                            // 推論する
                            val segmentedBitmap = mediaPipeImageSegmentation.segmentation(videoFrameBitmap, positionMs)

                            // Canvas に書き込む。背景を青にした推論結果を上に重ねて描画することで、BB 素材っぽく
                            drawBitmap(videoFrameBitmap, 0f, 0f, paint)
                            drawBitmap(segmentedBitmap, 0f, 0f, paint)
                        }

                        // 進捗を UI に
                        encodedPositionMs.longValue = positionMs
                        // とりあえず 10 秒まで動画を作る
                        positionMs <= 10_000
                    }
                )
            }
        }
    )

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {

            Button(onClick = {
                videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }) { Text(text = "動画を選ぶ") }

            if (encodedPositionMs.longValue != 0L) {
                Text(text = "処理済みの時間 : ${encodedPositionMs.longValue} ミリ秒")
            }
        }
    }
}