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
 */
package dorkbox.network.kryo;

import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer;

/** @author Nathan Sweet <misc@n4te.com> */
@SuppressWarnings({"rawtypes"})
public class CompatibleFieldSerializerTest extends KryoTestCase {
    {
        this.supportsCopy = true;
    }

    public void testCompatibleFieldSerializer () {
        TestClass object1 = new TestClass();
        object1.child = new TestClass();
        object1.other = new AnotherClass();
        object1.other.value = "meow";
        this.kryo.setDefaultSerializer(CompatibleFieldSerializer.class);
        this.kryo.register(TestClass.class);
        this.kryo.register(AnotherClass.class);
        roundTrip(100, 100, object1);
    }

    public void testAddedField () {
        TestClass object1 = new TestClass();
        object1.child = new TestClass();
        object1.other = new AnotherClass();
        object1.other.value = "meow";

        CompatibleFieldSerializer serializer = new CompatibleFieldSerializer(this.kryo, TestClass.class);
        serializer.removeField("text");
        this.kryo.register(TestClass.class, serializer);
        this.kryo.register(AnotherClass.class, new CompatibleFieldSerializer(this.kryo, AnotherClass.class));
        roundTrip(74, 74, object1);

        this.kryo.register(TestClass.class, new CompatibleFieldSerializer(this.kryo, TestClass.class));
        Object object2 = this.kryo.readClassAndObject(this.input);
        assertEquals(object1, object2);
    }

    public void testRemovedField () {
        TestClass object1 = new TestClass();
        object1.child = new TestClass();

        this.kryo.register(TestClass.class, new CompatibleFieldSerializer(this.kryo, TestClass.class));
        roundTrip(88, 88, object1);

        CompatibleFieldSerializer serializer = new CompatibleFieldSerializer(this.kryo, TestClass.class);
        serializer.removeField("text");
        this.kryo.register(TestClass.class, serializer);
        Object object2 = this.kryo.readClassAndObject(this.input);
        assertEquals(object1, object2);
    }

    static public class TestClass {
        public String text = "something";
        public int moo = 120;
        public long moo2 = 1234120;
        public TestClass child;
        public int zzz = 123;
        public AnotherClass other;

        @Override
        public boolean equals (Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            TestClass other = (TestClass)obj;
            if (this.child == null) {
                if (other.child != null) {
                    return false;
                }
            } else if (!this.child.equals(other.child)) {
                return false;
            }
            if (this.moo != other.moo) {
                return false;
            }
            if (this.moo2 != other.moo2) {
                return false;
            }
            if (this.text == null) {
                if (other.text != null) {
                    return false;
                }
            } else if (!this.text.equals(other.text)) {
                return false;
            }
            if (this.zzz != other.zzz) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public class AnotherClass {
        String value;
    }
}
