package com.hcjeong.forestix.data.fieldstudy

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-owned, cancellable metadata switch. Never an estimator/GPS permission. */
class FieldTargetSwitchCoordinator(private val scope:CoroutineScope){
    data class Choice(val targetId:String,val label:String)
    private val _busy=MutableStateFlow(false);val busy=_busy.asStateFlow()
    private val _pending=MutableStateFlow<Choice?>(null);val pending=_pending.asStateFlow()
    private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    private var pendingAction:(suspend()->Unit)?=null
    private var task:Job?=null;private var generation=0
    fun request(targetId:String,label:String,requiresConfirmation:Boolean,action:suspend()->Unit){
        if(_busy.value||_pending.value!=null)return
        _error.value=null
        if(requiresConfirmation){_pending.value=Choice(targetId,label);pendingAction=action}else begin(action)
    }
    fun confirm(){val action=pendingAction?:return;dismissConfirmation();begin(action)}
    fun dismissConfirmation(){_pending.value=null;pendingAction=null}
    fun dismissError(){_error.value=null}
    fun reportError(message:String){_error.value=message}
    fun cancel(){generation++;task?.cancel();task=null;dismissConfirmation();_busy.value=false}
    suspend fun waitForCurrentSwitch(){task?.join()}
    private fun begin(action:suspend()->Unit){
        generation++;val token=generation;_busy.value=true
        task=scope.launch{
            try{ensureActive();action()}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(generation==token)_error.value=e.message?:"Measurement target unavailable"}
            finally{if(generation==token){_busy.value=false;task=null}}
        }
    }
}
