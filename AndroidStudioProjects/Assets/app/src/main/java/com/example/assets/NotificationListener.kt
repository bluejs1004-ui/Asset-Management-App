package com.example.assets // 본인의 패키지명으로 변경하세요

import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        
        // 알림의 제목과 내용 가져오기
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // 타겟 앱 패키지명 (예: 카카오톡, NH투자증권, 미래에셋 등)
        val targetApps = listOf(
            "com.kakao.talk", 
            "com.nh투자증권.패키지명", // 실제 패키지명 확인 필요
            "com.miraeasset.패키지명"
        )

        // 감지된 알림이 타겟 앱이고, "매수" 또는 "매도" 단어가 포함되어 있다면
        if (targetApps.contains(packageName) && (text.contains("매수") || text.contains("매도"))) {
            Log.d("AutoTrade", "알림 감지: $text")
            
            // 메인 화면(WebView)으로 텍스트 전달
            val intent = Intent("com.yourpackage.TRADE_NOTIFICATION")
            intent.putExtra("tradeText", text)
            sendBroadcast(intent)
        }
    }
}