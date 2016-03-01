
package dorkbox.network.kryo;

import com.esotericsoftware.kryo.serializers.TaggedFieldSerializer;
import com.esotericsoftware.kryo.serializers.TaggedFieldSerializer.Tag;


@SuppressWarnings({"rawtypes"})
public class TaggedFieldSerializerTest extends KryoTestCase {
    {
        this.supportsCopy = true;
    }

    public void testTaggedFieldSerializer() {
        TestClass object1 = new TestClass();
        object1.moo = 2;
        object1.child = new TestClass();
        object1.child.moo = 5;
        object1.other = new AnotherClass();
        object1.other.value = "meow";
        object1.ignored = 32;
        this.kryo.setDefaultSerializer(TaggedFieldSerializer.class);
        this.kryo.register(TestClass.class);
        this.kryo.register(AnotherClass.class);
        TestClass object2 = roundTrip(57, 75, object1);
        assertTrue(object2.ignored == 0);
    }

    public void testAddedField() {
        TestClass object1 = new TestClass();
        object1.child = new TestClass();
        object1.other = new AnotherClass();
        object1.other.value = "meow";

        TaggedFieldSerializer serializer = new TaggedFieldSerializer(this.kryo, TestClass.class);
        serializer.removeField("text");
        this.kryo.register(TestClass.class, serializer);
        this.kryo.register(AnotherClass.class, new TaggedFieldSerializer(this.kryo, AnotherClass.class));
        roundTrip(39, 55, object1);

        this.kryo.register(TestClass.class, new TaggedFieldSerializer(this.kryo, TestClass.class));
        Object object2 = this.kryo.readClassAndObject(this.input);
        assertEquals(object1, object2);
    }

    static public class TestClass {
        @Tag(0) public String text = "something";
        @Tag(1) public int moo = 120;
        @Tag(2) public long moo2 = 1234120;
        @Tag(3) public TestClass child;
        @Tag(4) public int zzz = 123;
        @Tag(5) public AnotherClass other;
        @Tag(6) @Deprecated public int ignored;

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
        @Tag(1) String value;
    }
}
