import android.app.Notification
import android.content.Context

fun test(context: Context) {
    val builder = Notification.Builder(context, "channel")
    builder.setRequestPromotedOngoing(true)
    val style = Notification.ProgressStyle()
    style.setProgress(100, 50, false)
    builder.setStyle(style)
    builder.setShortCriticalText("50%")
}
