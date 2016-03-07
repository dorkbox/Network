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

import com.esotericsoftware.kryo.DefaultSerializer;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.NotNull;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import com.esotericsoftware.kryo.serializers.FieldSerializer.Optional;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** @author Nathan Sweet <misc@n4te.com> */

@SuppressWarnings({"rawtypes", "unchecked"})
public class FieldSerializerTest extends KryoTestCase {
    {
        this.supportsCopy = true;
    }

    public void testDefaultTypes () {
        this.kryo.register(DefaultTypes.class);
        this.kryo.register(byte[].class);
        DefaultTypes test = new DefaultTypes();
        test.booleanField = true;
        test.byteField = 123;
        test.charField = 'Z';
        test.shortField = 12345;
        test.intField = 123456;
        test.longField = 123456789;
        test.floatField = 123.456f;
        test.doubleField = 1.23456d;
        test.BooleanField = true;
        test.ByteField = -12;
        test.CharacterField = 'X';
        test.ShortField = -12345;
        test.IntegerField = -123456;
        test.LongField = -123456789l;
        test.FloatField = -123.3f;
        test.DoubleField = -0.121231d;
        test.StringField = "stringvalue";
        test.byteArrayField = new byte[] {2, 1, 0, -1, -2};
        roundTrip(78, 88, test);

        this.kryo.register(HasStringField.class);
        test.hasStringField = new HasStringField();
        FieldSerializer serializer = (FieldSerializer)this.kryo.getSerializer(DefaultTypes.class);
        serializer.getField("hasStringField").setCanBeNull(false);
        roundTrip(79, 89, test);
        serializer.setFixedFieldTypes(true);
        serializer.getField("hasStringField").setCanBeNull(false);
        roundTrip(78, 88, test);
    }

    public void testFieldRemoval () {
        this.kryo.register(DefaultTypes.class);
        this.kryo.register(byte[].class);
        this.kryo.register(HasStringField.class);

        HasStringField hasStringField = new HasStringField();
        hasStringField.text = "moo";
        roundTrip(4, 4, hasStringField);

        DefaultTypes test = new DefaultTypes();
        test.intField = 12;
        test.StringField = "value";
        test.CharacterField = 'X';
        test.child = new DefaultTypes();
        roundTrip(71, 91, test);

        this.supportsCopy = false;

        test.StringField = null;
        roundTrip(67, 87, test);

        FieldSerializer serializer = (FieldSerializer)this.kryo.getSerializer(DefaultTypes.class);
        serializer.removeField("LongField");
        serializer.removeField("floatField");
        serializer.removeField("FloatField");
        roundTrip(55, 75, test);

        this.supportsCopy = true;
    }

    public void testOptionalRegistration () {
        this.kryo.setRegistrationRequired(false);
        DefaultTypes test = new DefaultTypes();
        test.intField = 12;
        test.StringField = "value";
        test.CharacterField = 'X';
        test.hasStringField = new HasStringField();
        test.child = new DefaultTypes();
        test.child.hasStringField = new HasStringField();

        // HACK offset by XX from original because of changes in package name + XX because it has ANOTHER subclass
        roundTrip(195-this.packageOffset-this.packageOffset, 215-this.packageOffset-this.packageOffset, test);
        test.hasStringField = null;
        roundTrip(193-this.packageOffset-this.packageOffset, 213-this.packageOffset-this.packageOffset, test);

        test = new DefaultTypes();
        test.booleanField = true;
        test.byteField = 123;
        test.charField = 1234;
        test.shortField = 12345;
        test.intField = 123456;
        test.longField = 123456789;
        test.floatField = 123.456f;
        test.doubleField = 1.23456d;
        test.BooleanField = true;
        test.ByteField = -12;
        test.CharacterField = 123;
        test.ShortField = -12345;
        test.IntegerField = -123456;
        test.LongField = -123456789l;
        test.FloatField = -123.3f;
        test.DoubleField = -0.121231d;
        test.StringField = "stringvalue";
        test.byteArrayField = new byte[] {2, 1, 0, -1, -2};

        this.kryo = new Kryo();
        // HACK offset by X from original because of changes in package name
        roundTrip(140-this.packageOffset, 150-this.packageOffset, test);

        C c = new C();
        c.a = new A();
        c.a.value = 123;
        c.a.b = new B();
        c.a.b.value = 456;
        c.d = new D();
        c.d.e = new E();
        c.d.e.f = new F();

        // HACK offset by X from original because of changes in package name
        roundTrip(63-this.packageOffset, 73-this.packageOffset, c);
    }

    public void testReferences () {
        C c = new C();
        c.a = new A();
        c.a.value = 123;
        c.a.b = new B();
        c.a.b.value = 456;
        c.d = new D();
        c.d.e = new E();
        c.d.e.f = new F();
        c.d.e.f.a = c.a;

        this.kryo = new Kryo();
        // HACK offset by X from original because of changes in package name
        roundTrip(63-this.packageOffset, 73-this.packageOffset, c);
        C c2 = (C)this.object2;
        assertTrue(c2.a == c2.d.e.f.a);

        // Test reset clears unregistered class names.
        // HACK offset by X from original because of changes in package name
        roundTrip(63-this.packageOffset, 73-this.packageOffset, c);
        c2 = (C)this.object2;
        assertTrue(c2.a == c2.d.e.f.a);

        this.kryo = new Kryo();
        this.kryo.setRegistrationRequired(true);
        this.kryo.register(A.class);
        this.kryo.register(B.class);
        this.kryo.register(C.class);
        this.kryo.register(D.class);
        this.kryo.register(E.class);
        this.kryo.register(F.class);
        roundTrip(15, 25, c);
        c2 = (C)this.object2;
        assertTrue(c2.a == c2.d.e.f.a);
    }

    public void testRegistrationOrder () {
        A a = new A();
        a.value = 100;
        a.b = new B();
        a.b.value = 200;
        a.b.a = new A();
        a.b.a.value = 300;

        this.kryo.register(A.class);
        this.kryo.register(B.class);
        roundTrip(10, 16, a);

        this.kryo = new Kryo();
        this.kryo.setReferences(false);
        this.kryo.register(B.class);
        this.kryo.register(A.class);
        roundTrip(10, 16, a);
    }

    public void testExceptionTrace () {
        C c = new C();
        c.a = new A();
        c.a.value = 123;
        c.a.b = new B();
        c.a.b.value = 456;
        c.d = new D();
        c.d.e = new E();
        c.d.e.f = new F();

        Kryo kryoWithoutF = new Kryo();
        kryoWithoutF.setReferences(false);
        kryoWithoutF.setRegistrationRequired(true);
        kryoWithoutF.register(A.class);
        kryoWithoutF.register(B.class);
        kryoWithoutF.register(C.class);
        kryoWithoutF.register(D.class);
        kryoWithoutF.register(E.class);

        Output output = new Output(512);
        try {
            kryoWithoutF.writeClassAndObject(output, c);
            fail("Should have failed because F is not registered.");
        } catch (KryoException ignored) {
        }

        this.kryo.register(A.class);
        this.kryo.register(B.class);
        this.kryo.register(C.class);
        this.kryo.register(D.class);
        this.kryo.register(E.class);
        this.kryo.register(F.class);
        this.kryo.setRegistrationRequired(true);

        output.clear();
        this.kryo.writeClassAndObject(output, c);
        output.flush();
        assertEquals(14, output.total());

        Input input = new Input(output.getBuffer());
        this.kryo.readClassAndObject(input);

        try {
            input.setPosition(0);
            kryoWithoutF.readClassAndObject(input);
            fail("Should have failed because F is not registered.");
        } catch (KryoException ignored) {
        }
    }

    public void testNoDefaultConstructor () {
        this.kryo.register(SimpleNoDefaultConstructor.class, new Serializer<SimpleNoDefaultConstructor>() {
            @Override
            public SimpleNoDefaultConstructor read (Kryo kryo, Input input, Class<SimpleNoDefaultConstructor> type) {
                return new SimpleNoDefaultConstructor(input.readInt(true));
            }

            @Override
            public void write (Kryo kryo, Output output, SimpleNoDefaultConstructor object) {
                output.writeInt(object.constructorValue, true);
            }

            @Override
            public SimpleNoDefaultConstructor copy (Kryo kryo, SimpleNoDefaultConstructor original) {
                return new SimpleNoDefaultConstructor(original.constructorValue);
            }
        });
        SimpleNoDefaultConstructor object1 = new SimpleNoDefaultConstructor(2);
        roundTrip(2, 5, object1);

        this.kryo.register(ComplexNoDefaultConstructor.class, new FieldSerializer<ComplexNoDefaultConstructor>(this.kryo,
            ComplexNoDefaultConstructor.class) {
            @Override
            public void write (Kryo kryo, Output output, ComplexNoDefaultConstructor object) {
                output.writeString(object.name);
                super.write(kryo, output, object);
            }

            @Override
            protected ComplexNoDefaultConstructor create (Kryo kryo, Input input, Class type) {
                String name = input.readString();
                return new ComplexNoDefaultConstructor(name);
            }

            @Override
            protected ComplexNoDefaultConstructor createCopy (Kryo kryo, ComplexNoDefaultConstructor original) {
                return new ComplexNoDefaultConstructor(original.name);
            }
        });
        ComplexNoDefaultConstructor object2 = new ComplexNoDefaultConstructor("has no zero arg constructor!");
        object2.anotherField1 = 1234;
        object2.anotherField2 = "abcd";
        roundTrip(35, 37, object2);
    }

    public void testNonNull () {
        this.kryo.register(HasNonNull.class);
        HasNonNull nonNullValue = new HasNonNull();
        nonNullValue.nonNullText = "moo";
        roundTrip(4, 4, nonNullValue);
    }

    public void testDefaultSerializerAnnotation () {
        this.kryo = new Kryo();
        // HACK offset by X from original because of changes in package name
        roundTrip(82-this.packageOffset, 89-this.packageOffset, new HasDefaultSerializerAnnotation(123));
    }

    public void testOptionalAnnotation () {
        this.kryo = new Kryo();
        // HACK offset by X from original because of changes in package name + 2 b/c of how optionalAnnotations works
        roundTrip(72-this.packageOffset-2, 72-this.packageOffset-2, new HasOptionalAnnotation());
        this.kryo = new Kryo();
        this.kryo.getContext().put("smurf", null);
        // HACK offset by X from original because of changes in package name + 2 b/c of how optionalAnnotations works
        roundTrip(73-this.packageOffset-2, 76-this.packageOffset-2, new HasOptionalAnnotation());
    }

    public void testCyclicGraph () throws Exception {
        this.kryo = new Kryo();
        this.kryo.setRegistrationRequired(true);
        this.kryo.register(DefaultTypes.class);
        this.kryo.register(byte[].class);
        DefaultTypes test = new DefaultTypes();
        test.child = test;
        roundTrip(35, 45, test);
    }

    @SuppressWarnings("synthetic-access")
    public void testInstantiatorStrategy () {
        this.kryo.register(HasArgumentConstructor.class);
        this.kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
        HasArgumentConstructor test = new HasArgumentConstructor("cow");
        roundTrip(4, 4, test);

        this.kryo.register(HasPrivateConstructor.class);
        test = new HasPrivateConstructor();
        roundTrip(4, 4, test);
    }

    public void testGenericTypes () {
        this.kryo = new Kryo();
        this.kryo.setRegistrationRequired(true);
        this.kryo.register(HasGenerics.class);
        this.kryo.register(ArrayList.class);
        this.kryo.register(ArrayList[].class);
        this.kryo.register(HashMap.class);
        HasGenerics test = new HasGenerics();
        test.list1 = new ArrayList();
        test.list1.add(1);
        test.list1.add(2);
        test.list1.add(3);
        test.list1.add(4);
        test.list1.add(5);
        test.list1.add(6);
        test.list1.add(7);
        test.list1.add(8);
        test.list2 = new ArrayList();
        test.list2.add(test.list1);
        test.map1 = new HashMap();
        test.map1.put("a", test.list1);
        test.list3 = new ArrayList();
        test.list3.add(null);
        test.list4 = new ArrayList();
        test.list4.add(null);
        test.list5 = new ArrayList();
        test.list5.add("one");
        test.list5.add("two");
        roundTrip(53, 80, test);
        ArrayList[] al = new ArrayList[1];
        al[0] = new ArrayList(Arrays.asList(new String[] { "A", "B", "S" }));
        roundTrip(18, 18, al);
    }

    public void testRegistration () {
        int id = this.kryo.getNextRegistrationId();
        this.kryo.register(DefaultTypes.class, id);
        this.kryo.register(DefaultTypes.class, id);
        this.kryo.register(new Registration(byte[].class, this.kryo.getDefaultSerializer(byte[].class), id + 1));
        this.kryo.register(byte[].class, this.kryo.getDefaultSerializer(byte[].class), id + 1);
        this.kryo.register(HasStringField.class, this.kryo.getDefaultSerializer(HasStringField.class));

        DefaultTypes test = new DefaultTypes();
        test.intField = 12;
        test.StringField = "meow";
        test.CharacterField = 'z';
        test.byteArrayField = new byte[] {0, 1, 2, 3, 4};
        test.child = new DefaultTypes();
        roundTrip(75, 95, test);
    }

    static public class DefaultTypes {
        // Primitives.
        public boolean booleanField;
        public byte byteField;
        public char charField;
        public short shortField;
        public int intField;
        public long longField;
        public float floatField;
        public double doubleField;
        // Primitive wrappers.
        public Boolean BooleanField;
        public Byte ByteField;
        public Character CharacterField;
        public Short ShortField;
        public Integer IntegerField;
        public Long LongField;
        public Float FloatField;
        public Double DoubleField;
        // Other.
        public String StringField;
        public byte[] byteArrayField;

        DefaultTypes child;
        HasStringField hasStringField;

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
            DefaultTypes other = (DefaultTypes)obj;
            if (this.BooleanField == null) {
                if (other.BooleanField != null) {
                    return false;
                }
            } else if (!this.BooleanField.equals(other.BooleanField)) {
                return false;
            }
            if (this.ByteField == null) {
                if (other.ByteField != null) {
                    return false;
                }
            } else if (!this.ByteField.equals(other.ByteField)) {
                return false;
            }
            if (this.CharacterField == null) {
                if (other.CharacterField != null) {
                    return false;
                }
            } else if (!this.CharacterField.equals(other.CharacterField)) {
                return false;
            }
            if (this.DoubleField == null) {
                if (other.DoubleField != null) {
                    return false;
                }
            } else if (!this.DoubleField.equals(other.DoubleField)) {
                return false;
            }
            if (this.FloatField == null) {
                if (other.FloatField != null) {
                    return false;
                }
            } else if (!this.FloatField.equals(other.FloatField)) {
                return false;
            }
            if (this.IntegerField == null) {
                if (other.IntegerField != null) {
                    return false;
                }
            } else if (!this.IntegerField.equals(other.IntegerField)) {
                return false;
            }
            if (this.LongField == null) {
                if (other.LongField != null) {
                    return false;
                }
            } else if (!this.LongField.equals(other.LongField)) {
                return false;
            }
            if (this.ShortField == null) {
                if (other.ShortField != null) {
                    return false;
                }
            } else if (!this.ShortField.equals(other.ShortField)) {
                return false;
            }
            if (this.StringField == null) {
                if (other.StringField != null) {
                    return false;
                }
            } else if (!this.StringField.equals(other.StringField)) {
                return false;
            }
            if (this.booleanField != other.booleanField) {
                return false;
            }

            Object list1 = arrayToList(this.byteArrayField);
            Object list2 = arrayToList(other.byteArrayField);
            if (list1 != list2) {
                if (list1 == null || list2 == null) {
                    return false;
                }
                if (!list1.equals(list2)) {
                    return false;
                }
            }

            if (this.child != other.child) {
                if (this.child == null || other.child == null) {
                    return false;
                }
                if (this.child != this && !this.child.equals(other.child)) {
                    return false;
                }
            }

            if (this.byteField != other.byteField) {
                return false;
            }
            if (this.charField != other.charField) {
                return false;
            }
            if (Double.doubleToLongBits(this.doubleField) != Double.doubleToLongBits(other.doubleField)) {
                return false;
            }
            if (Float.floatToIntBits(this.floatField) != Float.floatToIntBits(other.floatField)) {
                return false;
            }
            if (this.intField != other.intField) {
                return false;
            }
            if (this.longField != other.longField) {
                return false;
            }
            if (this.shortField != other.shortField) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public final class A {
        public int value;
        public B b;

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
            A other = (A)obj;
            if (this.b == null) {
                if (other.b != null) {
                    return false;
                }
            } else if (!this.b.equals(other.b)) {
                return false;
            }
            if (this.value != other.value) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public final class B {
        public int value;
        public A a;

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
            B other = (B)obj;
            if (this.a == null) {
                if (other.a != null) {
                    return false;
                }
            } else if (!this.a.equals(other.a)) {
                return false;
            }
            if (this.value != other.value) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static final class C {
        public A a;
        public D d;

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
            C other = (C)obj;
            if (this.a == null) {
                if (other.a != null) {
                    return false;
                }
            } else if (!this.a.equals(other.a)) {
                return false;
            }
            if (this.d == null) {
                if (other.d != null) {
                    return false;
                }
            } else if (!this.d.equals(other.d)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public final class D {
        public E e;

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
            D other = (D)obj;
            if (this.e == null) {
                if (other.e != null) {
                    return false;
                }
            } else if (!this.e.equals(other.e)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public final class E {
        public F f;

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
            E other = (E)obj;
            if (this.f == null) {
                if (other.f != null) {
                    return false;
                }
            } else if (!this.f.equals(other.f)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public final class F {
        public int value;
        public final int finalValue = 12;
        public A a;

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
            F other = (F)obj;
            if (this.finalValue != other.finalValue) {
                return false;
            }
            if (this.value != other.value) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public class SimpleNoDefaultConstructor {
        int constructorValue;

        public SimpleNoDefaultConstructor (int constructorValue) {
            this.constructorValue = constructorValue;
        }

        public int getConstructorValue () {
            return this.constructorValue;
        }

        @Override
        public int hashCode () {
            final int prime = 31;
            int result = 1;
            result = prime * result + this.constructorValue;
            return result;
        }

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
            SimpleNoDefaultConstructor other = (SimpleNoDefaultConstructor)obj;
            if (this.constructorValue != other.constructorValue) {
                return false;
            }
            return true;
        }
    }

    static public class ComplexNoDefaultConstructor {
        public transient String name;
        public int anotherField1;
        public String anotherField2;

        public ComplexNoDefaultConstructor (String name) {
            this.name = name;
        }

        @Override
        public int hashCode () {
            final int prime = 31;
            int result = 1;
            result = prime * result + this.anotherField1;
            result = prime * result + (this.anotherField2 == null ? 0 : this.anotherField2.hashCode());
            result = prime * result + (this.name == null ? 0 : this.name.hashCode());
            return result;
        }

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
            ComplexNoDefaultConstructor other = (ComplexNoDefaultConstructor)obj;
            if (this.anotherField1 != other.anotherField1) {
                return false;
            }
            if (this.anotherField2 == null) {
                if (other.anotherField2 != null) {
                    return false;
                }
            } else if (!this.anotherField2.equals(other.anotherField2)) {
                return false;
            }
            if (this.name == null) {
                if (other.name != null) {
                    return false;
                }
            } else if (!this.name.equals(other.name)) {
                return false;
            }
            return true;
        }
    }

    static public class HasNonNull {
        @NotNull public String nonNullText;

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
            HasNonNull other = (HasNonNull)obj;
            if (this.nonNullText == null) {
                if (other.nonNullText != null) {
                    return false;
                }
            } else if (!this.nonNullText.equals(other.nonNullText)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public class HasStringField {
        public String text;

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
            HasStringField other = (HasStringField)obj;
            if (this.text == null) {
                if (other.text != null) {
                    return false;
                }
            } else if (!this.text.equals(other.text)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public class HasOptionalAnnotation {
        @Optional("smurf") int moo;

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
            HasOptionalAnnotation other = (HasOptionalAnnotation)obj;
            if (this.moo != other.moo) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    @DefaultSerializer(HasDefaultSerializerAnnotationSerializer.class)
    static public class HasDefaultSerializerAnnotation {
        long time;

        public HasDefaultSerializerAnnotation (long time) {
            this.time = time;
        }

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
            HasDefaultSerializerAnnotation other = (HasDefaultSerializerAnnotation)obj;
            if (this.time != other.time) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public class HasDefaultSerializerAnnotationSerializer extends Serializer<HasDefaultSerializerAnnotation> {
        @Override
        public void write (Kryo kryo, Output output, HasDefaultSerializerAnnotation object) {
            output.writeLong(object.time, true);
        }

        @Override
        public HasDefaultSerializerAnnotation read (Kryo kryo, Input input, Class type) {
            return new HasDefaultSerializerAnnotation(input.readLong(true));
        }

        @Override
        public HasDefaultSerializerAnnotation copy (Kryo kryo, HasDefaultSerializerAnnotation original) {
            return new HasDefaultSerializerAnnotation(original.time);
        }
    }

    static public class HasArgumentConstructor {
        public String moo;

        public HasArgumentConstructor (String moo) {
            this.moo = moo;
        }

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
            HasArgumentConstructor other = (HasArgumentConstructor)obj;
            if (this.moo == null) {
                if (other.moo != null) {
                    return false;
                }
            } else if (!this.moo.equals(other.moo)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    static public class HasPrivateConstructor extends HasArgumentConstructor {
        private HasPrivateConstructor () {
            super("cow");
        }
    }

    static public class HasGenerics {
        ArrayList<Integer> list1;
        List<List<?>> list2 = new ArrayList<List<?>>();
        List<?> list3 = new ArrayList();
        ArrayList<?> list4 = new ArrayList();
        ArrayList<String> list5;
        HashMap<String, ArrayList<Integer>> map1;

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
            HasGenerics other = (HasGenerics)obj;
            if (this.list1 == null) {
                if (other.list1 != null) {
                    return false;
                }
            } else if (!this.list1.equals(other.list1)) {
                return false;
            }
            if (this.list2 == null) {
                if (other.list2 != null) {
                    return false;
                }
            } else if (!this.list2.equals(other.list2)) {
                return false;
            }
            if (this.list3 == null) {
                if (other.list3 != null) {
                    return false;
                }
            } else if (!this.list3.equals(other.list3)) {
                return false;
            }
            if (this.list4 == null) {
                if (other.list4 != null) {
                    return false;
                }
            } else if (!this.list4.equals(other.list4)) {
                return false;
            }
            if (this.list5 == null) {
                if (other.list5 != null) {
                    return false;
                }
            } else if (!this.list5.equals(other.list5)) {
                return false;
            }
            if (this.map1 == null) {
                if (other.map1 != null) {
                    return false;
                }
            } else if (!this.map1.equals(other.map1)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }
}
