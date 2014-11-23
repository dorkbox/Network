
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
