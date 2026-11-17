package com.ayra.ai.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayra.ai.app.ai.AyraEvent
import com.ayra.ai.app.ai.AyraOrchestrator
import com.ayra.ai.app.ai.agent.AgentCitation
import com.ayra.ai.app.ai.agent.AgentPlanStep
import com.ayra.ai.app.ai.memory.LongTermMemoryManager
import com.ayra.ai.app.ai.memory.MemoryCandidate
import com.ayra.ai.app.ai.memory.MemorySaveResult
import com.ayra.ai.app.ai.providers.AyraError
import com.ayra.ai.app.ai.providers.ModelTier
import com.ayra.ai.app.automation.ProposedAction
import com.ayra.ai.app.cloud.AyraCloudRepository
import com.ayra.ai.app.data.AyraPreferences
import com.ayra.ai.app.data.ChatMessage
import com.ayra.ai.app.data.ChatRepository
import com.ayra.ai.app.data.ChatSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production ViewModel for Ghost AI Chat.
 * Manages persistent Room chat sessions, scoped message history, ViewModel-scoped orchestration
 * resilient to configuration changes, partial-token preservation on Stop, and opt-in memory confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    initialRepository: ChatRepository? = null
) : ViewModel() {

    private var repository: ChatRepository? = null

    private val sendMutex = Mutex()
    private var inFlightJob: Job? = null
    @Volatile
    private var activeModelHint: String = "Ghost AI"

    private val _activeSessionId = MutableStateFlow(1L)
    val activeSessionId: StateFlow<Long> = _activeSessionId.asStateFlow()

    private val _repositoryFlow = MutableStateFlow<ChatRepository?>(initialRepository)

    val sessions: StateFlow<List<ChatSession>> = _repositoryFlow
        .flatMapLatest { repo -> repo?.observeSessions() ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeSession: StateFlow<ChatSession?> = _repositoryFlow
        .flatMapLatest { repo ->
            if (repo == null) flowOf(null)
            else _activeSessionId.flatMapLatest { sid -> repo.observeSession(sid) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<ChatMessage>> = _repositoryFlow
        .flatMapLatest { repo ->
            if (repo == null) flowOf(emptyList())
            else _activeSessionId.flatMapLatest { sid -> repo.observeMessages(sid) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _thinkingStatus = MutableStateFlow<String?>(null)
    val thinkingStatus: StateFlow<String?> = _thinkingStatus.asStateFlow()

    private val _streamingAnswer = MutableStateFlow<String?>(null)
    val streamingAnswer: StateFlow<String?> = _streamingAnswer.asStateFlow()

    private val _agentPlanSteps = MutableStateFlow<List<AgentPlanStep>>(emptyList())
    val agentPlanSteps: StateFlow<List<AgentPlanStep>> = _agentPlanSteps.asStateFlow()

    private val _activeCitations = MutableStateFlow<List<AgentCitation>>(emptyList())
    val activeCitations: StateFlow<List<AgentCitation>> = _activeCitations.asStateFlow()

    private val _pendingMemoryCandidate = MutableStateFlow<MemoryCandidate?>(null)
    val pendingMemoryCandidate: StateFlow<MemoryCandidate?> = _pendingMemoryCandidate.asStateFlow()

    private val _showKeyOnboarding = MutableStateFlow(false)
    val showKeyOnboarding: StateFlow<Boolean> = _showKeyOnboarding.asStateFlow()

    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    private val _selectedDocumentUri = MutableStateFlow<Uri?>(null)
    val selectedDocumentUri: StateFlow<Uri?> = _selectedDocumentUri.asStateFlow()

    private val _selectedDocumentName = MutableStateFlow<String?>(null)
    val selectedDocumentName: StateFlow<String?> = _selectedDocumentName.asStateFlow()

    private val _selectedDocumentText = MutableStateFlow<String?>(null)
    val selectedDocumentText: StateFlow<String?> = _selectedDocumentText.asStateFlow()

    private val _pendingAction = MutableStateFlow<ProposedAction?>(null)
    val pendingAction: StateFlow<ProposedAction?> = _pendingAction.asStateFlow()

    private val _currentPersona = MutableStateFlow("AI_ASSISTANT")
    val currentPersona: StateFlow<String> = _currentPersona.asStateFlow()

    private val _selectedModelTier = MutableStateFlow("auto")
    val selectedModelTier: StateFlow<String> = _selectedModelTier.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        if (initialRepository != null) {
            attachRepository(initialRepository)
        }
    }

    fun attachRepository(chatRepository: ChatRepository) {
        if (repository === chatRepository) return
        repository = chatRepository
        _repositoryFlow.value = chatRepository
        viewModelScope.launch {
            val active = chatRepository.ensureActiveSession()
            _activeSessionId.value = active.id
        }
    }

    suspend fun ensureSessionReady(): Long {
        val repo = repository ?: return _activeSessionId.value
        val active = repo.ensureActiveSession()
        _activeSessionId.value = active.id
        return active.id
    }

    suspend fun persistMessage(message: ChatMessage): Long {
        val repo = repository ?: return 0L
        val sessionId = ensureSessionReady()
        return repo.appendMessage(sessionId, message)
    }

    suspend fun getRecentHistory(limit: Int = 15): List<ChatMessage> {
        val repo = repository ?: return emptyList()
        val sessionId = _activeSessionId.value
        return repo.getRecentMessages(sessionId, limit)
    }

    fun createNewSession(title: String = "New Conversation") {
        val repo = repository ?: return
        stopGeneration()
        viewModelScope.launch {
            val created = repo.createNewSession(
                title = title,
                personaMode = _currentPersona.value,
                modelTier = _selectedModelTier.value
            )
            _activeSessionId.value = created.id
        }
    }

    fun selectSession(sessionId: Long) {
        val repo = repository ?: return
        if (sessionId == _activeSessionId.value) return
        stopGeneration()
        viewModelScope.launch {
            val selected = repo.switchSession(sessionId)
            if (selected != null) {
                _activeSessionId.value = selected.id
            }
        }
    }

    fun clearCurrentSession() {
        val repo = repository ?: return
        val sessionId = _activeSessionId.value
        viewModelScope.launch {
            repo.clearSession(sessionId)
        }
    }

    fun deleteSession(sessionId: Long) {
        val repo = repository ?: return
        viewModelScope.launch {
            val next = repo.deleteSession(sessionId)
            _activeSessionId.value = next.id
        }
    }

    fun renameSession(sessionId: Long, newTitle: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.renameSession(sessionId, newTitle)
        }
    }

    fun togglePinSession(sessionId: Long) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.togglePinSession(sessionId)
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun clearInput() {
        _inputText.value = ""
    }

    fun setPersona(persona: String) {
        _currentPersona.value = persona.ifBlank { "AI_ASSISTANT" }
    }

    fun setModelTier(tier: String) {
        _selectedModelTier.value = ModelTier.canonicalizeId(tier)
    }

    fun setPendingAction(action: ProposedAction?) {
        _pendingAction.value = action
    }

    fun attachImage(uri: Uri?) {
        _selectedImageUri.value = uri
    }

    fun attachDocument(uri: Uri?, name: String?, text: String?) {
        _selectedDocumentUri.value = uri
        _selectedDocumentName.value = name
        _selectedDocumentText.value = text
    }

    fun clearAttachments() {
        _selectedImageUri.value = null
        _selectedDocumentUri.value = null
        _selectedDocumentName.value = null
        _selectedDocumentText.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun setShowKeyOnboarding(show: Boolean) {
        _showKeyOnboarding.value = show
    }

    fun setThinkingStatus(status: String?) {
        _thinkingStatus.value = status
    }

    fun setStreamingAnswer(answer: String?) {
        _streamingAnswer.value = answer
    }

    fun appendStreamingToken(token: String) {
        val current = _streamingAnswer.value ?: ""
        _streamingAnswer.value = current + token
    }

    fun setAgentPlanSteps(steps: List<AgentPlanStep>) {
        _agentPlanSteps.value = steps
    }

    fun inspectCandidateMemory(context: Context, prompt: String) {
        if (!AyraPreferences.isMemoryEnabled(context)) return
        _pendingMemoryCandidate.value = LongTermMemoryManager.extractCandidateFact(prompt)
    }

    fun confirmPendingMemorySave(context: Context, onFeedback: (String) -> Unit = {}) {
        val candidate = _pendingMemoryCandidate.value ?: return
        _pendingMemoryCandidate.value = null
        viewModelScope.launch {
            when (val res = LongTermMemoryManager.trySaveMemory(
                context = context,
                category = candidate.category,
                rawContent = candidate.content,
                sourceSessionId = _activeSessionId.value
            )) {
                is MemorySaveResult.Saved -> onFeedback("Saved to encrypted memory: ${res.entity.content}")
                is MemorySaveResult.Rejected -> onFeedback(res.reason)
            }
        }
    }

    fun dismissPendingMemorySave() {
        _pendingMemoryCandidate.value = null
    }

    fun setInFlightJob(job: Job?) {
        inFlightJob = job
    }

    /**
     * Runs [AyraOrchestrator.handle] inside [viewModelScope] so configuration changes never
     * drop active streaming or persistence, while holding clean cancellation handle in [inFlightJob].
     */
    fun launchOrchestratedInference(
        orchestrator: AyraOrchestrator,
        prompt: String,
        persona: String,
        tier: String,
        history: List<ChatMessage>,
        imageBase64: String?,
        documentText: String?,
        documentName: String?,
        cloudRepo: AyraCloudRepository?,
        onSpeakResponse: (String) -> Unit
    ) {
        _agentPlanSteps.value = emptyList()
        _activeCitations.value = emptyList()
        activeModelHint = "Ghost AI"
        val job = viewModelScope.launch {
            var receivedTerminalEvent = false
            try {
                orchestrator.handle(
                    prompt = prompt,
                    persona = persona,
                    tier = tier,
                    history = history,
                    imageBase64 = imageBase64,
                    documentText = documentText,
                    documentName = documentName
                ).collect { event ->
                    when (event) {
                        is AyraEvent.PlanUpdated -> {
                            _agentPlanSteps.value = event.steps
                        }
                        is AyraEvent.CitationAdded -> {
                            val current = _activeCitations.value
                            if (current.none { it.url == event.citation.url }) {
                                _activeCitations.value = current + event.citation
                            }
                        }
                        is AyraEvent.Status -> {
                            _thinkingStatus.value = event.message
                            activeModelHint = event.message.removePrefix("Thinking with ").removeSuffix("...").take(60)
                        }
                        is AyraEvent.Token -> {
                            appendStreamingToken(event.text)
                        }
                        is AyraEvent.ActionRequired -> {
                            receivedTerminalEvent = true
                            _pendingAction.value = event.action
                        }
                        is AyraEvent.Complete -> {
                            receivedTerminalEvent = true
                            val assistantMsg = ChatMessage(
                                role = "assistant",
                                content = event.response.text,
                                craftType = event.response.craftType,
                                craftPayload = event.response.craftPayload,
                                actionCommand = event.response.modelUsed
                            )
                            val insertedAssistantId = persistMessage(assistantMsg)
                            cloudRepo?.let { repo ->
                                viewModelScope.launch(Dispatchers.IO) {
                                    runCatching {
                                        repo.syncMessageToCloud(assistantMsg.copy(id = insertedAssistantId))
                                        val cType = event.response.craftType
                                        if (cType in listOf("IMAGE", "VIDEO", "SONG", "3D", "PDF_DOWNLOAD")) {
                                            repo.logCreation(
                                                creationType = if (cType == "PDF_DOWNLOAD") "PDF" else cType!!,
                                                title = prompt.take(120),
                                                prompt = prompt,
                                                summary = event.response.text.take(800),
                                                modelUsed = event.response.modelUsed ?: "Ghost AI"
                                            )
                                        }
                                    }
                                }
                            }
                            onSpeakResponse(event.response.text)
                        }
                        is AyraEvent.Error -> {
                            receivedTerminalEvent = true
                            persistMessage(
                                ChatMessage(
                                    role = "assistant",
                                    content = "⚠️ ${event.error.userFriendlyMessage}"
                                )
                            )
                            if (event.error is AyraError.NoProviderConfigured) {
                                _showKeyOnboarding.value = true
                            }
                        }
                    }
                }
                if (!receivedTerminalEvent) {
                    persistMessage(
                        ChatMessage(
                            role = "assistant",
                            content = "⚠️ কোনো উত্তর পাওয়া যায়নি। অনুগ্রহ করে ইন্টারনেট সংযোগ পরীক্ষা করে আবার চেষ্টা করুন।"
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeErr = e.localizedMessage ?: e::class.java.simpleName
                persistMessage(
                    ChatMessage(
                        role = "assistant",
                        content = "⚠️ Error processing request: $safeErr"
                    )
                )
            } finally {
                withContext(NonCancellable) {
                    finishSending()
                }
            }
        }
        inFlightJob = job
    }

    /**
     * Cancels active inference AND preserves any already-streamed partial answer cleanly in Room.
     */
    fun stopGeneration() {
        val partialSnapshot = _streamingAnswer.value?.trim()
        val modelSnapshot = activeModelHint
        inFlightJob?.cancel()
        inFlightJob = null
        _isGenerating.value = false
        _thinkingStatus.value = null
        _streamingAnswer.value = null
        _agentPlanSteps.value = emptyList()
        _activeCitations.value = emptyList()
        if (!partialSnapshot.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    persistMessage(
                        ChatMessage(
                            role = "assistant",
                            content = "$partialSnapshot\n\n*(Stopped by user)*",
                            actionCommand = "$modelSnapshot (Partial)"
                        )
                    )
                }
            }
        }
    }

    suspend fun tryStartSending(): Boolean = sendMutex.withLock {
        if (_isGenerating.value) {
            return false
        }
        _isGenerating.value = true
        _errorMessage.value = null
        _streamingAnswer.value = null
        _agentPlanSteps.value = emptyList()
        _activeCitations.value = emptyList()
        _thinkingStatus.value = "Thinking..."
        return true
    }

    fun finishSending(error: String? = null) {
        _isGenerating.value = false
        _thinkingStatus.value = null
        _streamingAnswer.value = null
        _agentPlanSteps.value = emptyList()
        _activeCitations.value = emptyList()
        _errorMessage.value = error
        inFlightJob = null
    }

    companion object {
        fun factory(repository: ChatRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        return ChatViewModel(repository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
