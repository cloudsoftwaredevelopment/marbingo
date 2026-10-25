package ph.marbingo.field

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ph.marbingo.field.ui.FieldApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#1C1B18")),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.argb(0x1A, 0, 0, 0)),
        )
        setContent { FieldApp() }
    }
}
