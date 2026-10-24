package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.common.IdleResourceCache
import com.hcjeong.forestix.data.fieldstudy.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class FieldRuntimeSafetyTest {
    @Test fun concurrentTrimDoesNotCloseOrReplaceRunningInference(){
        val closed=java.util.concurrent.atomic.AtomicInteger()
        val cache=IdleResourceCache<Any>{closed.incrementAndGet()}
        val started=CountDownLatch(1);val finish=CountDownLatch(1);val executor=Executors.newSingleThreadExecutor()
        try{
            val job=executor.submit{cache.withResource({Any()}){started.countDown();check(finish.await(5,TimeUnit.SECONDS))}}
            assertTrue(started.await(5,TimeUnit.SECONDS));cache.trim()
            cache.withResource({error("Must not replace running inference")}){}
            assertEquals(0,closed.get());finish.countDown();job.get(5,TimeUnit.SECONDS);assertEquals(1,closed.get())
        }finally{finish.countDown();executor.shutdownNow()}
    }
    @Test fun cacheDefersCloseUntilLastInferenceLeaseIncludingExceptions(){
        var created=0;var closed=0
        val cache=IdleResourceCache<Any>{closed++}
        fun create():Any{created++;return Any()}
        cache.withResource(::create){first->
            cache.trim()
            cache.withResource(::create){second->assertSame(first,second)}
            assertEquals(0,closed)
        }
        assertEquals(1,created);assertEquals(1,closed)
        try{cache.withResource(::create){cache.trim();error("synthetic inference failure")};fail()}catch(_:IllegalStateException){}
        assertEquals(2,closed)
        try{cache.withResource({error("synthetic creation failure")}){};fail()}catch(_:IllegalStateException){}
        cache.withResource(::create){};assertEquals(3,created)
        cache.trim();cache.trim();assertEquals(3,closed)
    }
    @Test fun retentionPreservesSourcesUnknownNamesSharingGraceAndLinks(){
        val root=Files.createTempDirectory("forestix-cache-test-").toFile()
        try{
            val now=System.currentTimeMillis();val sentinel=File(root,"manifest.json").apply{writeText("synthetic evidence")}
            val zips=listOf(60_000L,7200_000L,8000_000L,9000_000L).map{age->
                File(root,"forestix-field-study-${newFieldId()}.zip").apply{writeBytes(byteArrayOf(1,2,3));check(setLastModified(now-age))}}
            val unknown=File(root,"forestix-field-study-not-a-uuid.zip").apply{writeText("preserve")}
            val directory=File(root,"forestix-field-study-${newFieldId()}.zip").apply{mkdirs()}
            val link=File(root,"forestix-field-study-${newFieldId()}.zip")
            Files.createSymbolicLink(link.toPath(),sentinel.toPath())
            FieldExportRetention.prune(root,now)
            assertTrue(zips[0].exists());assertTrue(zips[1].exists());assertFalse(zips[2].exists());assertFalse(zips[3].exists())
            for(file in listOf(sentinel,unknown,directory,link))assertTrue(file.exists())
            FieldExportRetention.prune(root,now,keepNewest=0);assertTrue(zips[0].exists())
            assertEquals("synthetic evidence",sentinel.readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun snapshotMergeKeepsConcurrentIDsAndNewerRevisionsWithoutResurrectingOldRemovedIDs(){
        fun record()=FieldMeasurementRecord(id=newFieldId(),sessionId=newFieldId(),treeId="SYNTHETIC",kind=FieldStudyKind.DBH,
            attemptNumber=1,configuration=FieldStudyConfiguration(),runtimeSettings=emptyMap(),revision=1)
        val old=record();val updated=old.copy(revision=2);val new=record();val removed=record()
        assertEquals(listOf(updated,new),FieldMeasurementSnapshot.merge(listOf(old),listOf(updated,new,removed),setOf(old.id,removed.id)))
        val large=(0 until 5000).map{record()}
        assertEquals(large,FieldMeasurementSnapshot.merge(large,large,large.mapTo(HashSet()){it.id}))
    }
}
