package id.codeagent.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.permTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            CodeAgentTheme(vm.theme) {
                Surface(Modifier.fillMaxSize()) { AppRoot(vm) }
            }
        }
    }

    override fun onStart() { super.onStart(); vm.inForeground = true }
    override fun onStop() { vm.inForeground = false; super.onStop() }
    override fun onResume() { super.onResume(); vm.permTick++ }
}
