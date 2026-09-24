package com.livetranslate.headphones

import android.app.Application
import com.livetranslate.headphones.assistant.AssistantSession
import com.livetranslate.headphones.assistant.AssistantSettings
import com.livetranslate.headphones.assistant.LlmClient
import com.livetranslate.headphones.assistant.NetworkMonitor
import com.livetranslate.headphones.glasses.GlassesFacade
import com.livetranslate.headphones.glasses.GlassesRepository
import com.livetranslate.headphones.glasses.vendor.OudmonSdkBootstrap
import com.livetranslate.headphones.translation.LanguageModelManager

class LiveTranslateApp : Application() {
    lateinit var languageModelManager: LanguageModelManager
        private set
    lateinit var translationSession: TranslationSession
        private set
    lateinit var appSettings: AppSettings
        private set
    lateinit var assistantSettings: AssistantSettings
        private set
    lateinit var networkMonitor: NetworkMonitor
        private set
    lateinit var llmClient: LlmClient
        private set
    lateinit var glassesFacade: GlassesFacade
        private set
    lateinit var assistantSession: AssistantSession
        private set

    override fun onCreate() {
        super.onCreate()
        OudmonSdkBootstrap.init(this)
        languageModelManager = LanguageModelManager(this)
        translationSession = TranslationSession(this, languageModelManager)
        appSettings = AppSettings(this)
        assistantSettings = AssistantSettings(this)
        networkMonitor = NetworkMonitor(this)
        llmClient = LlmClient(assistantSettings, networkMonitor)
        glassesFacade = GlassesRepository.getInstance(this)
        GadgetbridgeBleReceiver.register(this)
        assistantSession = AssistantSession(
            this,
            llmClient,
            assistantSettings,
            glassesFacade,
            languageModelManager,
        )
    }

    companion object {
        fun from(app: Application): LiveTranslateApp = app as LiveTranslateApp
    }
}
