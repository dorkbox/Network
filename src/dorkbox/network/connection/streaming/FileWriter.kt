/*
 * Copyright 2023 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dorkbox.network.connection.streaming

import kotlinx.atomicfu.atomic
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class FileWriter(val size: Int, val file: File) : StreamingWriter, RandomAccessFile(file, "rw") {

    private val written = atomic(0)

    init {
        // reserve space on disk!
        val saveSize = size.coerceAtMost(4096)
        var bytes = ByteArray(saveSize)
        this.write(bytes)

        if (saveSize < size) {
            var remainingBytes = size - saveSize

            while (remainingBytes > 0) {
                if (saveSize > remainingBytes) {
                    bytes = ByteArray(remainingBytes)
                }
                this.write(bytes)
                remainingBytes = (remainingBytes - saveSize).coerceAtLeast(0)
            }
        }
    }

    override fun writeBytes(startPosition: Int, bytes: ByteArray) {
        // the OS will synchronize writes to disk
        this.seek(startPosition.toLong())
        write(bytes)
        written.addAndGet(bytes.size)
    }

    override fun isFinished(): Boolean {
        return written.value == size
    }

    fun finishAndClose() {
        fd.sync()
        close()
    }
}
