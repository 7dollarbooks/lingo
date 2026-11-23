package com.livetranslate.headphones

import android.app.Application
import com.livetranslate.headphones.assistant.AssistantSession
import com.livetranslate.headphones.assistant.AssistantSettings
import com.livetranslate.headphones.assistant.LlmClient
import com.livetranslate.headphones.assistant.ModelRouter
import com.livetranslate.headphones.assistant.NetworkMonitor
import com.livetranslate.headphones.assistant.OllamaClient
import com.livetranslate.headphones.glasses.GlassesFacade
import com.livetranslate.headphones.glasses.GlassesRepository
import com.livetranslate.headphones.glasses.vendor.OudmonSdkBootstrap
import com.livetranslate.headphones.memory.MemorySession
import com.livetranslate.headphones.memory.MemoryStore
import com.livetranslate.headphones.navigation.NavigationSession
import com.livetranslate.headphones.text.TextSink
import com.livetranslate.headphones.translation.LanguageModelManager
import com.livetranslate.headphones.watch.MoyoungWatchClient
import com.livetranslate.headphones.watch.WatchWeatherPusher

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
    lateinit var modelRouter: ModelRouter
        private set
    lateinit var glassesFacade: GlassesFacade
        private set
    lateinit var assistantSession: AssistantSession
        private set
    lateinit var watchClient: MoyoungWatchClient
        private set
    lateinit var textSink: TextSink
        private set
    lateinit var memoryStore: MemoryStore
        private set
    lateinit var memorySession: MemorySession
        private set
    lateinit var navigationSession: NavigationSession
        private set
    lateinit var watchWeather: WatchWeatherPusher
        private set

    override fun onCreate() {
        super.onCreate()
        OudmonSdkBootstrap.init(this)
        languageModelManager = LanguageModelManager(this)
        appSettings = AppSettings(this)
        watchClient = MoyoungWatchClient(this)
        watchWeather = WatchWeatherPusher(this, watchClient)
        watchClient.onWeatherRequest = { watchWeather.onWatchAsked() }
        watchClient.onReady = { watchWeather.onConnected() }
        textSink = TextSink(watchClient)
        memoryStore = MemoryStore(this)
        memoryStore.purgeExpired(System.currentTimeMillis())
        translationSession = TranslationSession(this, languageModelManager, textSink)
        watchClient.onMessageSent = { line -> translationSession.announceOnShokz(line) }
        assistantSettings = AssistantSettings(this)
        networkMonitor = NetworkMonitor(this)
        llmClient = LlmClient(assistantSettings, networkMonitor)
        modelRouter = ModelRouter(assistantSettings, llmClient, OllamaClient(assistantSettings))
        glassesFacade = GlassesRepository.getInstance(this)
        GadgetbridgeBleReceiver.register(this)
        assistantSession = AssistantSession(
            this,
            modelRouter,
            assistantSettings,
            glassesFacade,
            languageModelManager,
        )
        memorySession = MemorySession(
            context = this,
            glasses = glassesFacade,
            llm = llmClient,
            store = memoryStore,
            watch = watchClient,
            textSink = textSink,
            phonePhoto = { assistantSession.capturePhoneJpeg() },
        )
        assistantSession.bindMemory(memorySession)
        navigationSession = NavigationSession(
            context = this,
            settings = appSettings,
            textSink = textSink,
            watch = watchClient,
            speech = translationSession,
        )
    }

    companion object {
        fun from(app: Application): LiveTranslateApp = app as LiveTranslateApp
    }
}
