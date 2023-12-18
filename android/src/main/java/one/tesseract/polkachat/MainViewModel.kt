package one.tesseract.polkachat

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

//TODO: double check that errors are caught everywhere
class MainViewModel(private val core: Core) : ViewModel() {
    private val _messages = mutableStateListOf<Message>()
    val messages: List<Message> = _messages

    private val _account = mutableStateOf<String?>(null)
    val account: State<String?> = _account

    private val _failure = Channel<Throwable>()
    val failure: Flow<Throwable> = _failure.receiveAsFlow()

    init {
        val messagesState = _messages
        this.viewModelScope.launch {
            try {
                val committedSize = messagesState.filterIsInstance<Message.CommittedMessage>().size.toUInt()
                val messages = core.messages(committedSize).map { Message.CommittedMessage(it) }
                messagesState.addAll(messages)
            } catch (e: Exception) {
                val message = e.message ?: ""
                if (!message.contains("Cancelled Tesseract error")) {
                    errorException(e)
                }
            }
        }
    }

    private suspend fun errorException(exception: Throwable) {
        _failure.send(exception)
    }

    fun login() {
        viewModelScope.launch {
            try {
                _account.value = core.account()
            } catch (e: Exception) {
                errorException(e)
            }
        }
    }

    fun sendMessage(message: String) {
        @Suppress("NAME_SHADOWING") val message = Message.SubmittedMessage(message)

        viewModelScope.launch {
            try {
                _messages.add(message)
                core.send(message.text)
                val index = _messages.lastIndexOf(message)
                _messages[index] = message.intoCommitted()
            } catch (e: Exception) {
                errorException(e)

                _messages.remove(message)
            }
        }
    }

    fun presentError(message: String) {
        viewModelScope.launch {
            _failure.send(Exception("dApp Error: $message"))
        }
    }
}

class MainViewModelFactory(private val core: Core) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if(modelClass.isAssignableFrom(MainViewModel::class.java)){
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(core) as T
        }
        throw TypeCastException("Can't create view model ${MainViewModel::class.java.name} and cast to ${modelClass.name}")
    }
}