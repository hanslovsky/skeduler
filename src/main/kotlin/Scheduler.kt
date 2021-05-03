package me.hanslovsky.skeduler

import jep.DirectNDArray
import jep.SharedInterpreter
import net.imglib2.img.array.ArrayImg
import net.imglib2.type.NativeType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.real.FloatType
import org.ntakt.*
import org.ntakt.access.FloatBufferAccess
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.*
import kotlin.random.Random

class NoNextAvailable : Exception()

interface Scheduler<T> {
    fun next(timeout: Long, unit: TimeUnit): T
}

class RandomStateScheduler<T>(
    private val numWorkers: Int,
    private val minCacheSize: Int = 10,
    private val rng: Random = Random.Default,
    private val generator: (Long) -> T,
    private val settings: Settings = Settings()
) : Scheduler<T>, Closeable {

    data class Settings(
        var fetchTimeOutMilliSeconds: Long = 10,
        var pushTimeOutMilliSeconds: Long = 10
    )


    private inner class WorkerThread(name: String) : Thread(threadGroup, name) {

        init {
            this.isDaemon = true
            start()
        }

        override fun run() {
            while (!isShutdown) {
                val nextElement = randomSeeds.poll()
                try {
                    outputQueue.add(generator(nextElement))
                } catch (e: Exception) {
                    nextElement?.let { randomSeeds.add(it) }
                }
                sleep(settings.fetchTimeOutMilliSeconds)
            }
        }
    }

    private inner class SubmitterThread(name: String) : Thread(threadGroup, name) {
        init {
            this.isDaemon = true
            start()
        }

        override fun run() {
            while (!isShutdown) {
                repeat(minCacheSize - (outputQueue.size + randomSeeds.size)) { randomSeeds.add(rng.nextLong()) }
                sleep(settings.pushTimeOutMilliSeconds)
            }
        }
    }

    private val randomSeeds = LinkedBlockingQueue<Long>()
    private val outputQueue = LinkedBlockingQueue<T>()
    private val threadGroup = ThreadGroup("random-state-scheduler")
    private val submitter = SubmitterThread("random-state-scheduler-submitter")
    private val workers = (0 until numWorkers).map { WorkerThread("random-state-scheduler-worker-$it") }
    private var isShutdown = false

    override fun next(timeout: Long, unit: TimeUnit) = outputQueue.poll(timeout, unit) ?: throw NoNextAvailable()

    fun shutdown() {
        isShutdown = true
        submitter.join()
        workers.forEach { it.join() }
    }
    override fun close() = shutdown()
}

class Trainer<T, U>(private val scheduler: Scheduler<T>, private val train: (T) -> U) {
    fun trainNext(timeout: Long, unit: TimeUnit): U {
        val t = scheduler.next(timeout, unit)
        return train(t)
    }
}


fun main() {
    val timeout = 100L
    val unit = TimeUnit.MILLISECONDS

    val means = mutableListOf<Float>()
    val interpreter = SharedInterpreter().also { it.exec("import numpy as np") }
    val generator = { seed: Long ->
        val rng = Random(seed)
        val t = FloatType()
        val dims = intArrayOf(100, 200)
        val buf = ByteBuffer.allocateDirect(t.bitsPerPixel / 8 * dims.fold(1) { a, b -> a * b })
        // do imglib2 processing
        val img = ntakt.float32s(buf.asFloatBuffer().access, *dims) { rng.nextFloat() }
        Thread.sleep(3000)
        img to buf.asFloatBuffer()
    }


    RandomStateScheduler(12, generator = generator)
        .use { scheduler ->
            val trainer = Trainer(scheduler) {
                val ndArray = DirectNDArray(it.second, *it.first.dimsAsInts)
                interpreter.set("array", ndArray)
                interpreter.exec("mean = np.mean(array)")
                interpreter.getValue("mean", java.lang.Float::class.java).toFloat()
            }
            while (means.size < 20) {
                try {
                    means += trainer.trainNext(timeout, unit)
                } catch (e: NoNextAvailable) {
                    // pass
                }
            }
        }

    println(means)



}
