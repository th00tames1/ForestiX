package com.hcjeong.forestix.fieldstudy

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.MainActivity
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.data.AppSettings
import com.hcjeong.forestix.data.cruise.CruiseDatabase
import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.geo.FieldTargetFileReader
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Opt-in REAL UI checks, restricted to a new, disposable emulator. Never run
 * against a physical device or an existing unowned application database. */
internal class RemoteFieldUiChecks(private val runner:Instrumentation){
    private val targetContext get()=runner.targetContext
    private val uiAutomation get()=runner.uiAutomation
    private fun startActivitySync(intent:Intent)=runner.startActivitySync(intent)
    private fun runOnMainSync(action:()->Unit)=runner.runOnMainSync(action)
    private lateinit var token:String
    private lateinit var report:File
    fun run(disposableToken:String):Bundle{
        token=disposableToken
        val result=Bundle();var activity:Activity?=null
        try{runBlocking{
            check(Build.HARDWARE in listOf("ranchu","goldfish")){"Disposable emulator required; physical devices are prohibited"}
            check(UUID.fromString(token).toString()==token){"Canonical ownership UUID required"}
            val marker=File(targetContext.cacheDir,"remote-preflight-owner")
            val owned=marker.isFile&&marker.readText()==token
            check(owned||(!targetContext.getDatabasePath("forestix.db").exists()&&!targetContext.getDatabasePath(CruiseDatabase.NAME).exists()&&
                !File(targetContext.filesDir,"field-study").exists())){"Existing app data is not owned by this disposable test"}
            if(!owned)marker.writeText(token)
            uiAutomation.serviceInfo=uiAutomation.serviceInfo.apply{
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            report=File(targetContext.cacheDir,"remote-preflight-$token").apply{mkdirs()}
            disableOwnedEmulatorNetwork()
            val setup=AppSettings(targetContext);setup.setRegionPickerSeen(true);setup.setUnitSystem(UnitSystem.METRIC);setup.setDeveloperMode(false)
            delay(500)
            val store=FieldStudyStore(File(targetContext.filesDir,"field-study"))
            withTimeout(5000){while(!store.state.value.loaded)delay(10)}
            val config=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC-REMOTE-$token",operatorId="TEST",ar=FieldARConfiguration(enabled=true))
            store.saveConfiguration(config,false)
            val source=FieldTargetFileReader.read("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"TREE_ID":"REMOTE-0003","LABEL":"PREFLIGHT-0003"},"geometry":{"type":"Point","coordinates":[-123.28556,44.56417]}}]}""".toByteArray(),"synthetic.geojson")
            store.commitImport(FieldTargetMaterializer.make(source,FieldTargetMapping("TREE_ID",labelField="LABEL"),config,false),false)
            val target=store.state.value.activeTargets.single()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            awaitNode("map settings button"){it.contentDescription?.toString()=="Settings"}.recycle()
            shot("map-offline-before-selection")
            clickText("Settings")
            scrollTo("Field study");clickText("Field study")
            awaitText("Study ID");awaitText("Operator ID")
            for(i in 0..<18){
                check(find{it.text?.toString() in listOf("DBH reference methods","Height reference methods") }==null){"Normal mode exposed developer GT definitions"}
                if(!scroll(true))break
                delay(120)
            }
            shot("normal-field-settings")
            backToMap()
            repeat(7){clickText("My location");delay(40)}
            val env=AppEnvironment.create(targetContext)
            withTimeout(5000){while(!env.settings.state.value.developerMode)delay(20)}
            // The synthetic target is exactly at the fresh map's documented
            // fallback centre; derive the tap from the actual Activity bounds.
            val bounds=Rect();runOnMainSync{bounds.set(requireNotNull(activity).windowManager.currentWindowMetrics.bounds)}
            tap(bounds.exactCenterX(),bounds.exactCenterY())
            awaitText("Tree ID: REMOTE-0003");shot("selected-synthetic-map-target")
            clickText("Measure DBH")
            withTimeout(10000){while(env.fieldStudy.state.value.selectedTargetId!=target.id)delay(50)}
            // Even with no GPS grant/fix, target routing must reach the scan
            // screen or its honest camera/depth capability UI, never be GPS-gated.
            awaitNode("DBH route / capability screen"){n->
                val s=(n.text?.toString()?:"")+" "+(n.contentDescription?.toString()?:"")
                s.contains("DBH",true)||s.contains("LiDAR",true)||s.contains("ARCore",true)||s.contains("Set ground",true)
            }.recycle()
            shot("dbh-no-gps-route")
            backToMap()
            clickText("Settings");scrollTo("Field study");clickText("Field study")
            scrollTo("DBH reference methods");awaitText("DBH reference methods")
            shot("developer-reference-controls")
            for((kind,name) in listOf("DBH" to "Synthetic DBH comparison","height" to "Synthetic height comparison")){
                val add="Add $kind reference method"
                scrollTo(add);clickText(add)
                scrollTo("Comparison 2");replaceEditable(name,"Comparison 2")
            }
            scrollTo("Save configuration");clickText("Save configuration")
            scrollTo("Configuration saved.");awaitText("Configuration saved.")
            val methods=env.fieldStudy.state.value.configuration.referenceMethods
            check(methods.size==4&&FieldStudyKind.entries.all{kind->methods.count{it.kind==kind}==2}){"Separate DBH/height methods were not saved"}
            scrollTo("Target catalog / Reference observations");clickText("Target catalog / Reference observations")
            awaitText("Target catalog");clickText("PREFLIGHT-0003");awaitText("Tree ID: REMOTE-0003")
            val expected=mapOf("DBH reference" to 25.0,"Synthetic DBH comparison" to 26.0,
                "Height reference" to 20.0,"Synthetic height comparison" to 21.0)
            for((name,value) in expected){
                scrollTo("Add reference observation");clickText("Add reference observation")
                awaitText("Reference observation");clickText("Method")
                val unit=if(name.contains("DBH"))"cm"else"m"
                clickText("$name ($unit)");replaceEditable(value.toString())
                scrollTo("Save observation");clickText("Save observation")
                awaitText("Tree ID: REMOTE-0003")
            }
            val references=env.fieldStudy.state.value.referenceObservations
            check(references.size==4&&references.all{it.targetId==target.id}&&references.associate{it.method.name to it.inputValue}==expected){"Entered GT values or exact tree binding differ"}
            shot("four-separate-native-reference-observations")
            clickText("Close");awaitText("Target catalog");clickText("Close")
            scrollTo("Export records, raw and integrity audit");clickText("Export records, raw and integrity audit")
            awaitNode("complete export share action",30000){it.text?.toString()=="Share field-study ZIP"}.recycle()
            // An unsupported emulator camera may honestly journal a failed
            // attempt. The GT export assertion must not assume that count is 0.
            scrollTo("4 reference observations",contains=true)
            shot("complete-export-ready")
            clickText("Share field-study ZIP")
            withTimeout(10000){while(uiAutomation.rootInActiveWindow?.packageName?.toString()==targetContext.packageName)delay(100)}
            shot("native-share-chooser")
            val durable=FieldStudyArchive(File(targetContext.filesDir,"field-study"))
            check(durable.loadConfiguration().studyId==config.studyId&&durable.loadTargets().any{it.id==target.id})
            val referenceScan=durable.scanReferences()
            check(referenceScan.issues.isEmpty()&&referenceScan.values.associate{it.method.name to it.inputValue}==expected){"Native GT observations did not reach durable storage"}
            result.putBoolean("all_passed",true)
            result.putString("checks","actual_MainActivity_startup,verified_no_active_network,offline_map_target_hit,normal_GT_hidden,seven_tap_unlock_without_GPS,correct_DBH_target_routing,two_DBH_and_two_height_methods,four_native_GT_inputs_exact_values_tree_and_durable_storage,complete_export_with_four_references,native_share_chooser,durable_configuration_and_target")
            result.putString("report_directory",report.absolutePath)
        }}catch(e:Throwable){
            result.putBoolean("all_passed",false);result.putString("failure",e.stackTraceToString()+"\nVISIBLE_UI:\n"+dump())
            if(::report.isInitialized)runCatching{shot("failure")}
        }
        return result
    }
    private suspend fun disableOwnedEmulatorNetwork(){
        // run() has already verified emulator hardware AND its ownership UUID.
        // Never execute these radio commands on the user's physical phone.
        for(command in listOf("svc wifi disable","svc data disable")){
            android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use{it.readBytes()}
        }
        uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.ACCESS_NETWORK_STATE)
        try{
            val connectivity=checkNotNull(targetContext.getSystemService(android.net.ConnectivityManager::class.java))
            withTimeout(10000){while(connectivity.activeNetwork!=null)delay(100)}
            File(report,"offline-state.txt").writeText("Owned disposable emulator: Wi-Fi and mobile data disabled; ConnectivityManager.activeNetwork == null. Physical devices untouched.\n")
        }finally{uiAutomation.dropShellPermissionIdentity()}
    }
    private suspend fun replaceEditable(value:String,current:String?=null){
        val node=awaitNode("editable field ${current?:"reference value"}"){
            it.isEditable&&it.isEnabled&&it.isVisibleToUser&&(current==null||it.text?.toString()?.contains(current)==true)
        }
        try{
            val arguments=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,arguments)){"Native text entry refused"}
        }finally{node.recycle()}
        awaitNode("entered value $value"){it.isEditable&&it.text?.toString()?.contains(value)==true}.recycle()
    }
    private fun nodes():List<AccessibilityNodeInfo>{
        val all=ArrayList<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo){all+=n;for(i in 0..<n.childCount)n.getChild(i)?.let(::walk)}
        uiAutomation.rootInActiveWindow?.let(::walk);return all
    }
    private fun find(test:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo?{
        val all=nodes();try{return all.firstOrNull(test)?.let{AccessibilityNodeInfo.obtain(it)}}finally{all.forEach{it.recycle()}}
    }
    private suspend fun awaitNode(label:String,timeout:Long=15000,test:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo{
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end){
            // Permission denial is intentional: measurement routing must not
            // depend on a position grant. Operates only inside the owned emulator.
            dismissPermissionPrompt()
            find(test)?.let{return it};delay(150)
        }
        error("UI timeout: $label\n"+dump())
    }
    private suspend fun awaitText(s:String){awaitNode(s){it.text?.toString()==s||it.hintText?.toString()==s||it.contentDescription?.toString()==s}.recycle()}
    private fun click(n:AccessibilityNodeInfo){
        var current=n
        try{while(!current.isClickable){val parent=current.parent?:error("No clickable ancestor for ${current.text}");current.recycle();current=parent}
            check(current.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"Accessibility click refused"}
        }finally{current.recycle()}
    }
    private suspend fun clickText(s:String){click(awaitNode(s){it.text?.toString()==s||it.contentDescription?.toString()==s});delay(180)}
    private fun scroll(forward:Boolean):Boolean{
        val node=find{it.isScrollable&&it.isVisibleToUser}?:return false
        val bounds=Rect();try{node.getBoundsInScreen(bounds)}finally{node.recycle()}
        if(bounds.height()<100||bounds.width()<20)return false
        // Exercise the actual touch-scroll path. Compose's generic
        // ACTION_SCROLL_FORWARD can be acknowledged without advancing a
        // newly recreated ScrollView after the camera route closes.
        val x=bounds.exactCenterX();val start=bounds.top+bounds.height()*(if(forward).75f else .25f)
        val end=bounds.top+bounds.height()*(if(forward).25f else .75f)
        val down=SystemClock.uptimeMillis()
        for(step in 0..10){
            val action=when(step){0->MotionEvent.ACTION_DOWN;10->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE}
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,start+(end-start)*step/10f,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            try{if(!uiAutomation.injectInputEvent(event,step==0||step==10))return false}finally{event.recycle()}
            if(step<10)SystemClock.sleep(30)
        }
        // Let the fling settle before testing a header/button's visibility;
        // a lazy row can otherwise cross the viewport only transiently.
        SystemClock.sleep(700)
        return true
    }
    private suspend fun scrollTo(s:String,contains:Boolean=false){
        for(forward in listOf(true,false))repeat(26){
            dismissPermissionPrompt()
            val node=find{it.isVisibleToUser&&listOfNotNull(it.text?.toString(),it.contentDescription?.toString()).any{text->if(contains)text.contains(s)else text==s}}
            if(node!=null){node.recycle();return}
            if(!scroll(forward))return@repeat;delay(180)
        }
        error("UI field not reachable: $s\n"+dump())
    }
    private suspend fun backToMap(){
        fun onMap():Boolean{
            if(uiAutomation.rootInActiveWindow?.packageName?.toString()!=targetContext.packageName)return false
            val node=find{it.isVisibleToUser&&it.contentDescription?.toString()=="My location"}?:return false
            node.recycle();return true
        }
        repeat(4){
            dismissPermissionPrompt()
            if(onMap())return
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            // A real sheet/navigation transition can exceed 350 ms on a cold
            // emulator. Observe the settled map before sending another Back;
            // do not accidentally exercise root double-Back exit in this test.
            val deadline=SystemClock.uptimeMillis()+4000
            while(SystemClock.uptimeMillis()<deadline){dismissPermissionPrompt();if(onMap())return;delay(150)}
        }
        check(onMap()){"Map not restored\n"+dump()}
    }
    private suspend fun dismissPermissionPrompt(){
        // Android uses a different deny ID after a repeated request. Deny
        // both, including prompts delivered after the route first appears.
        find{it.isVisibleToUser&&it.packageName?.toString() in listOf("com.android.permissioncontroller","com.google.android.permissioncontroller")&&
            it.viewIdResourceName?.contains(":id/permission_deny")==true}?.let{click(it);delay(150)}
    }
    private fun tap(x:Float,y:Float){val t=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
            val e=MotionEvent.obtain(t,SystemClock.uptimeMillis(),action,x,y,0)
            try{check(uiAutomation.injectInputEvent(e,true))}finally{e.recycle()}
        }
    }
    private fun shot(name:String){
        repeat(3){
            val image=uiAutomation.takeScreenshot()
            if(image!=null){
                try{File(report,"$name.png").outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
                finally{image.recycle()};return
            }
            SystemClock.sleep(200)
        }
        // Diagnostic pixels are useful but not a substitute for the native
        // UI/storage assertions. Report missing emulator pixels explicitly.
        File(report,"$name-screenshot-unavailable.txt").writeText("UiAutomation returned no pixels after three attempts. No screenshot/visual pass is claimed for this step.\n")
    }
    private fun dump():String{val all=nodes();try{return all.mapNotNull{n->
        val s=listOfNotNull(n.text?.toString(),n.contentDescription?.toString(),n.hintText?.toString()).joinToString(" | ")
        s.takeIf{it.isNotBlank()}
    }.distinct().take(100).joinToString("\n")}finally{all.forEach{it.recycle()}}}
}
