package com.group33.medicationreminder.caregiver.plan
import android.content.Intent
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.shared.contract.MedicationPhotoView
import org.junit.Assert.*
import org.junit.Test
class PhotoTextSuggestionsTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
 private fun ui(block:()->Unit)=i.runOnMainSync(block)
 @Test fun confirmingPhotoAutomaticallyFillsName() = scenario(false,false)
 @Test fun typingDuringRecognitionIsNotOverwritten() = scenario(true,false)
 @Test fun uncheckingPhotoDiscardsRecognition() = scenario(false,true)
 private fun scenario(type:Boolean, cancel:Boolean) {
  val host=i.startActivitySync(Intent(i.targetContext,PlanEditorDebugActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlanEditorDebugActivity
  val bitmap=Bitmap.createBitmap(1400,300,Bitmap.Config.ARGB_8888)
  Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("Medicine name: Example tablets",30f,140f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=60f }) }
  lateinit var editor:PlanEditorView
  lateinit var name:EditText
  try {
   ui {
    editor=all(host.window.decorView).filterIsInstance<PlanEditorView>().single()
    editor.bind(host,"Test Elder",MedicationPhotoView("test","elder","image/png",1),bitmap,"Asia/Shanghai","Asia/Shanghai",PlanFormViewModel(),PlanEditorFixture().model)
   }
   i.waitForIdleSync()
   ui {
    name=all(editor).filterIsInstance<EditText>().first()
    assertEquals("",name.text.toString())
    val check=all(editor).filterIsInstance<CheckBox>().first()
    check.isChecked=true
    if(type) name.setText("My manual name")
    if(cancel) check.isChecked=false
   }
   val deadline=System.nanoTime()+30_000_000_000L
   var done=false
   while(!done && System.nanoTime()<deadline) {
    Thread.sleep(200)
    ui { done=all(editor).filterIsInstance<PhotoTextSuggestions>().single().text.toString()!=host.getString(com.group33.medicationreminder.R.string.ocr_loading) }
   }
   assertTrue(done)
   if(cancel) Thread.sleep(1500)
   ui { assertEquals(if(type) "My manual name" else if(cancel) "" else "Example tablets",name.text.toString()) }
  } finally { ui { editor.clear();host.finish() };bitmap.recycle() }
 }
}
