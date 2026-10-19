package com.example.detectplantdiase

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.detectplantdiase.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Man goc cua app: trang Trang chu / Lich su + nut Camera o giua thanh duoi.
 * Cai dat, Camera, Ket qua deu mo chong len man nay, nen bam quay lai luon ve day thay vi thoat app.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_HISTORY = "history"
    }

    private lateinit var binding: ActivityMainBinding

    // Dang o trang Lich su: bam quay lai thi ve Trang chu, o Trang chu bam quay lai moi thoat app
    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Nap model AI ngay khi mo app, de luc chup xong khong phai cho nap model qua 343 MB
        lifecycleScope.launch { AiEngine.load(applicationContext) }

        // Nguoi dung dang bat tu dong chup nhung service da bi dung (Android dong app, khong cho chay luc
        // khoi dong may...): mo app thi chay lai
        if (savedInstanceState == null && ConfigManager.isMonitoringEnabled(this) && !GardenMonitorService.isServiceRunning) {
            GardenMonitorService.startMonitoring(this)
        }

        if (savedInstanceState == null) {
            val history = HistoryFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, HomeFragment(), TAG_HOME)
                .add(R.id.fragmentContainer, history, TAG_HISTORY)
                .hide(history)
                .commitNow()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showTab(TAG_HOME)
                R.id.nav_history -> showTab(TAG_HISTORY)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        binding.fabCamera.setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, backToHome)
        backToHome.isEnabled = supportFragmentManager.findFragmentByTag(TAG_HISTORY)?.isHidden == false
    }

    fun openHistoryTab() {
        binding.bottomNav.selectedItemId = R.id.nav_history
    }

    private fun showTab(tag: String) {
        val home = supportFragmentManager.findFragmentByTag(TAG_HOME) ?: return
        val history = supportFragmentManager.findFragmentByTag(TAG_HISTORY) ?: return
        val showHome = tag == TAG_HOME
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .show(if (showHome) home else history)
            .hide(if (showHome) history else home)
            .commit()
        backToHome.isEnabled = !showHome
    }
}
