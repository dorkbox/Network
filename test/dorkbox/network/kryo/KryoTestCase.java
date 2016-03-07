/* Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */package dorkbox.network.kryo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.*;
import junit.framework.TestCase;
import org.junit.Assert;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;

/**
 * Convenience methods for round tripping objects.
 *
 * @author Nathan Sweet <misc@n4te.com>
 */

@SuppressWarnings({"rawtypes", "unchecked"})
abstract public class KryoTestCase extends TestCase {

    public static final int ORIGINAL_PACKAGE_LENGTH = "com.esotericsoftware.kryo".length();

    // HACK offset by 5 from original because of changes in package name + 5 because it has ANOTHER subclass
    protected int packageOffset = ORIGINAL_PACKAGE_LENGTH - this.getClass().getPackage().getName().length();

	protected Kryo kryo;
	protected Output output;
	protected Input input;
	protected Object object1, object2;
	protected boolean supportsCopy;

	static interface StreamFactory {
		Output createOutput(OutputStream os);

		Output createOutput(OutputStream os, int size);

		Output createOutput(int size, int limit);

		Input createInput(InputStream os, int size);

		Input createInput(byte[] buffer);
	}

	@Override
    protected void setUp() throws Exception {
        // assume SLF4J is bound to logback in the current environment
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        LoggerContext context = rootLogger.getLoggerContext();

        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(context);
        context.reset(); // override default configuration

        rootLogger.setLevel(Level.TRACE);

		kryo = new Kryo();
		kryo.setReferences(false);
		kryo.setRegistrationRequired(true);
//		 kryo.setAsmEnabled(false);
	}

	public <T> T roundTrip(int length, int unsafeLength, T object1) {
		roundTripWithStreamFactory(unsafeLength, object1, new StreamFactory() {
			@Override
            public Output createOutput(OutputStream os) {
				return new UnsafeMemoryOutput(os);
			}

			@Override
            public Output createOutput(OutputStream os, int size) {
				return new UnsafeMemoryOutput(os, size);
			}

			@Override
            public Output createOutput(int size, int limit) {
				return new UnsafeMemoryOutput(size, limit);
			}

			@Override
            public Input createInput(InputStream os, int size) {
				return new UnsafeMemoryInput(os, size);
			}

			@Override
            public Input createInput(byte[] buffer) {
				return new UnsafeMemoryInput(buffer);
			}
		});

		roundTripWithStreamFactory(unsafeLength, object1, new StreamFactory() {
			@Override
            public Output createOutput(OutputStream os) {
				return new UnsafeOutput(os);
			}

			@Override
            public Output createOutput(OutputStream os, int size) {
				return new UnsafeOutput(os, size);
			}

			@Override
            public Output createOutput(int size, int limit) {
				return new UnsafeOutput(size, limit);
			}

			@Override
            public Input createInput(InputStream os, int size) {
				return new UnsafeInput(os, size);
			}

			@Override
            public Input createInput(byte[] buffer) {
				return new UnsafeInput(buffer);
			}
		});

		roundTripWithStreamFactory(length, object1, new StreamFactory() {
			@Override
            public Output createOutput(OutputStream os) {
				return new ByteBufferOutput(os);
			}

			@Override
            public Output createOutput(OutputStream os, int size) {
				return new ByteBufferOutput(os, size);
			}

			@Override
            public Output createOutput(int size, int limit) {
				return new ByteBufferOutput(size, limit);
			}

			@Override
            public Input createInput(InputStream os, int size) {
				return new ByteBufferInput(os, size);
			}

			@Override
            public Input createInput(byte[] buffer) {
				return new ByteBufferInput(buffer);
			}
		});

		roundTripWithStreamFactory(unsafeLength, object1, new StreamFactory() {
			@Override
            public Output createOutput(OutputStream os) {
				return new FastOutput(os);
			}

			@Override
            public Output createOutput(OutputStream os, int size) {
				return new FastOutput(os, size);
			}

			@Override
            public Output createOutput(int size, int limit) {
				return new FastOutput(size, limit);
			}

			@Override
            public Input createInput(InputStream os, int size) {
				return new FastInput(os, size);
			}

			@Override
            public Input createInput(byte[] buffer) {
				return new FastInput(buffer);
			}
		});

		return roundTripWithStreamFactory(length, object1, new StreamFactory() {
			@Override
            public Output createOutput(OutputStream os) {
				return new Output(os);
			}

			@Override
            public Output createOutput(OutputStream os, int size) {
				return new Output(os, size);
			}

			@Override
            public Output createOutput(int size, int limit) {
				return new Output(size, limit);
			}

			@Override
            public Input createInput(InputStream os, int size) {
				return new Input(os, size);
			}

			@Override
            public Input createInput(byte[] buffer) {
				return new Input(buffer);
			}
		});
	}

	public <T> T roundTripWithStreamFactory(int length, T object1,
			StreamFactory sf) {
		this.object1 = object1;

		// Test output to stream, large buffer.
		ByteArrayOutputStream outStream = new ByteArrayOutputStream();
		output = sf.createOutput(outStream, 4096);
		kryo.writeClassAndObject(output, object1);
		output.flush();

		// Test input from stream, large buffer.
		@SuppressWarnings("unused")
        byte[] out = outStream.toByteArray();
		input = sf.createInput(
				new ByteArrayInputStream(outStream.toByteArray()), 4096);
		object2 = kryo.readClassAndObject(input);
		assertEquals("Incorrect number of bytes read.", length, input.total());
		assertEquals("Incorrect number of bytes written.", length, output.total());
		assertEquals(object1, object2);

		// Test output to stream, small buffer.
		outStream = new ByteArrayOutputStream();
		output = sf.createOutput(outStream, 10);
		kryo.writeClassAndObject(output, object1);
		output.flush();

		// Test input from stream, small buffer.
		input = sf.createInput(
				new ByteArrayInputStream(outStream.toByteArray()), 10);
		object2 = kryo.readClassAndObject(input);
		assertEquals("Incorrect number of bytes read.", length, input.total());
		assertEquals(object1, object2);

		if (object1 != null) {
			// Test null with serializer.
			Serializer serializer = kryo.getRegistration(object1.getClass())
					.getSerializer();
			output.clear();
			outStream.reset();
			kryo.writeObjectOrNull(output, null, serializer);
			output.flush();

			// Test null from byte array with and without serializer.
			input = sf.createInput(
					new ByteArrayInputStream(outStream.toByteArray()), 10);
			assertEquals(null, kryo.readObjectOrNull(input, object1.getClass(),
					serializer));

			input = sf.createInput(
					new ByteArrayInputStream(outStream.toByteArray()), 10);
			assertEquals(null, kryo.readObjectOrNull(input, object1.getClass()));
		}

		// Test output to byte array.
		output = sf.createOutput(length * 2, -1);
		kryo.writeClassAndObject(output, object1);
		output.flush();

		// Test input from byte array.
		input = sf.createInput(output.toBytes());
		object2 = kryo.readClassAndObject(input);
		assertEquals(object1, object2);
		assertEquals("Incorrect length.", length, output.total());
		assertEquals("Incorrect number of bytes read.", length, input.total());
		input.rewind();

		if (supportsCopy) {
			// Test copy.
			T copy = kryo.copy(object1);
			assertEquals(object1, copy);
			copy = kryo.copyShallow(object1);
			assertEquals(object1, copy);
		}

		return (T) object2;
	}

	static public void assertEquals(Object object1, Object object2) {
		Assert.assertEquals(arrayToList(object1), arrayToList(object2));
	}

	static public Object arrayToList(Object array) {
		if (array == null || !array.getClass().isArray()) {
            return array;
        }
		ArrayList list = new ArrayList(Array.getLength(array));
		for (int i = 0, n = Array.getLength(array); i < n; i++) {
            list.add(arrayToList(Array.get(array, i)));
        }
		return list;
	}

	static public ArrayList list(Object... items) {
		ArrayList list = new ArrayList();
		for (Object item : items) {
            list.add(item);
        }
		return list;
	}
}
