package life.bloomary.app.features.linkedinauth.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.OkHttpClient

/** LinkedIn brand blue from the SwiftUI fallback: rgb(0.039, 0.400, 0.761). */
private val FallbackBlue = Color(0xFF0A66C2)

private const val USER_AGENT = "Bloomary/1.0"

/** Mirrors `timeoutInterval: 12` in LinkedInProfilePhoto.swift. */
private const val TIMEOUT_SECONDS = 12L

/**
 * Mirrors ios/Features/LinkedInAuth/UI/LinkedInProfilePhoto.swift.
 *
 * The image is fetched with `User-Agent: Bloomary/1.0`, a 12 s timeout, and
 * cache-else-load semantics (`max-stale` accepts any cached entry, otherwise
 * the network is hit — the OkHttp/Coil equivalent of
 * `.returnCacheDataElseLoad`). Any failure shows the "in" initials fallback,
 * matching the Swift `uiImage = nil` paths. Reloading on `pictureURL` change
 * mirrors `.task(id: pictureURL)`.
 */
@Composable
fun LinkedInProfilePhoto(
    pictureURL: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageLoader = remember { linkedInProfileImageLoader(context.applicationContext) }
    val painter = rememberAsyncImagePainter(
        model = pictureURL,
        imageLoader = imageLoader,
    )
    val painterState by painter.state.collectAsState()

    Box(
        modifier = modifier.size(size).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(FallbackBlue, CircleShape))
        Text(
            text = "in",
            color = Color.White,
            fontSize = maxOf(size.value * 0.42f, 10f).sp,
            fontWeight = FontWeight.Bold,
        )
        if (painterState is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun linkedInProfileImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory = {
                        OkHttpClient.Builder()
                            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .cache(
                                Cache(
                                    File(context.cacheDir, "linkedin_profile_photo"),
                                    10L * 1024 * 1024,
                                ),
                            )
                            .addInterceptor { chain ->
                                chain.proceed(
                                    chain.request().newBuilder()
                                        .header("User-Agent", USER_AGENT)
                                        .header("Cache-Control", "max-stale=${Int.MAX_VALUE}")
                                        .build(),
                                )
                            }
                            .build()
                    },
                ),
            )
        }
        .build()
