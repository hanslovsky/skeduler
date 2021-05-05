package me.hanslovsky.skeduler

import jep.DirectNDArray
import jep.SharedInterpreter
import net.imglib2.type.numeric.real.FloatType
import org.ntakt.*
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

    val outputs = mutableListOf<Any>()
    val interpreter = SharedInterpreter()
    interpreter.exec(
        """
            import numpy as np
            from tensorflow import keras
            from tensorflow.keras import layers
            from tensorflow.keras.optimizers import SGD
            model = keras.Sequential(
                [
                    keras.Input(shape=(100, 200)),
                    layers.Dense(1, activation=None, name="layer1", use_bias=True),
                ]
            )
            opt = SGD(learning_rate=0.01, momentum=0.9)
            model.compile(optimizer=opt, loss='mse')
        """.trimIndent()
    )
    interpreter.exec("import numpy as np")
    interpreter.exec("import tensorflow as tf")
    val generator = { seed: Long ->
        val rng = Random(seed)
        val t = FloatType()
        val dims = intArrayOf(100, 200)
        val buf = ByteBuffer.allocateDirect(t.bitsPerPixel / 8 * dims.fold(1) { a, b -> a * b })
        val bufGt = ByteBuffer.allocateDirect(t.bitsPerPixel / 8 * dims.fold(1) { a, b -> a * b})
        // do imglib2 processing
        val img = ntakt.float32s(buf.asFloatBuffer().access, *dims) { rng.nextFloat() }
        val gt = ntakt.float32s(buf.asFloatBuffer().access, *dims) { (it % 2).toFloat() }
        (img to buf.asFloatBuffer()) to (gt to bufGt.asFloatBuffer())
    }


    RandomStateScheduler(12, generator = generator)
        .use { scheduler ->
            val trainer = Trainer(scheduler) {
                val raw = it.first
                val gt = it.second
                val ndArray = DirectNDArray(raw.second, *raw.first.dimsAsInts)
                val ndArrayGt = DirectNDArray(gt.second, *gt.first.dimsAsInts)
                interpreter.set("array", ndArray)
                interpreter.set("gt", ndArrayGt)
                interpreter.exec("mean = model.fit(array, gt)")
                interpreter.getValue("mean")
            }
            while (outputs.size < 10) {
                try {
                    outputs += trainer.trainNext(timeout, unit)
                } catch (e: NoNextAvailable) {
                    // pass
                }
            }
        }

    println(outputs)



}
