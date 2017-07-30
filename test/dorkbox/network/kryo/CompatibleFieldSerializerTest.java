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
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package dorkbox.network.kryo;

import java.io.FileNotFoundException;

import com.esotericsoftware.kryo.serializers.CompatibleFieldSerializer;
import com.esotericsoftware.kryo.serializers.FieldSerializer;

/**
 * @author Nathan Sweet <misc@n4te.com>
 */
public
class CompatibleFieldSerializerTest extends KryoTestCase {
    {
        supportsCopy = true;
    }

    public
    void testCompatibleFieldSerializer() throws FileNotFoundException {
        TestClass object1 = new TestClass();
        object1.child = new TestClass();
        object1.other = new AnotherClass();
        object1.other.value = "meow";
        kryo.setDefaultSerializer(CompatibleFieldSerializer.class);
        kryo.register(TestClass.class);
        kryo.register(AnotherClass.class);
        roundTrip(107, 107, object1);
    }

    public
    void testAddedField() throws FileNotFoundException {
        TestClass object1 = new TestClass();
        object1.child = new TestClass();
        object1.other = new AnotherClass();
        object1.other.value = "meow";

        CompatibleFieldSerializer serializer = new CompatibleFieldSerializer(kryo, TestClass.class);
        serializer.removeField("text");
        kryo.register(TestClass.class, serializer);
        kryo.register(AnotherClass.class, new CompatibleFieldSerializer(kryo, AnotherClass.class));
        roundTrip(80, 80, object1);

        kryo.register(TestClass.class, new CompatibleFieldSerializer(kryo, TestClass.class));
        Object object2 = kryo.readClassAndObject(input);
        assertEquals(object1, object2);
    }

    public
    void testAddedFieldToClassWithManyFields() throws FileNotFoundException {
        // class must have more than CompatibleFieldSerializer#THRESHOLD_BINARY_SEARCH number of fields
        ClassWithManyFields object1 = new ClassWithManyFields();
        object1.aa = "aa";
        object1.a0 = "a0";
        object1.bb = "bb";
        object1.b0 = "b0";
        object1.cc = "cc";
        object1.c0 = "c0";
        object1.dd = "dd";
        object1.d0 = "d0";
        object1.ee = "ee";
        object1.e0 = "e0";
        object1.ff = "ff";
        object1.f0 = "f0";
        object1.gg = "gg";
        object1.g0 = "g0";
        object1.hh = "hh";
        object1.h0 = "h0";
        object1.ii = "ii";
        object1.i0 = "i0";
        object1.jj = "jj";
        object1.j0 = "j0";
        object1.kk = "kk";
        object1.k0 = "k0";
        object1.ll = "ll";
        object1.mm = "mm";
        object1.nn = "nn";
        object1.oo = "oo";
        object1.pp = "pp";
        object1.qq = "qq";
        object1.rr = "rr";
        object1.ss = "ss";
        object1.tt = "tt";
        object1.uu = "uu";
        object1.vv = "vv";
        object1.ww = "ww";
        object1.xx = "xx";
        object1.yy = "yy";
        object1.zz = "zzaa";

        CompatibleFieldSerializer serializer = new CompatibleFieldSerializer(kryo, ClassWithManyFields.class);
        serializer.removeField("bAdd");
        kryo.register(ClassWithManyFields.class, serializer);
        roundTrip(226, 226, object1);

        kryo.register(ClassWithManyFields.class, new CompatibleFieldSerializer(kryo, ClassWithManyFields.class));
        Object object2 = kryo.readClassAndObject(input);
        assertEquals(object1, object2);
    }

    public
    void testRemovedField() throws FileNotFoundException {
        TestClass object1 = new TestClass();
        object1.child = new TestClass();

        kryo.register(TestClass.class, new CompatibleFieldSerializer(kryo, TestClass.class));
        roundTrip(94, 94, object1);

        CompatibleFieldSerializer serializer = new CompatibleFieldSerializer(kryo, TestClass.class);
        serializer.removeField("text");
        kryo.register(TestClass.class, serializer);
        Object object2 = kryo.readClassAndObject(input);
        assertEquals(object1, object2);
    }

    public
    void testRemovedFieldFromClassWithManyFields() throws FileNotFoundException {
        // class must have more than CompatibleFieldSerializer#THRESHOLD_BINARY_SEARCH number of fields
        ClassWithManyFields object1 = new ClassWithManyFields();
        object1.aa = "aa";
        object1.a0 = "a0";
        object1.bAdd = "bAdd";
        object1.bb = "bb";
        object1.b0 = "b0";
        object1.cc = "cc";
        object1.c0 = "c0";
        object1.dd = "dd";
        object1.d0 = "d0";
        object1.ee = "ee";
        object1.e0 = "e0";
        object1.ff = "ff";
        object1.f0 = "f0";
        object1.gg = "gg";
        object1.g0 = "g0";
        object1.hh = "hh";
        object1.h0 = "h0";
        object1.ii = "ii";
        object1.i0 = "i0";
        object1.jj = "jj";
        object1.j0 = "j0";
        object1.kk = "kk";
        object1.k0 = "k0";
        object1.ll = "ll";
        object1.mm = "mm";
        object1.nn = "nn";
        object1.oo = "oo";
        object1.pp = "pp";
        object1.qq = "qq";
        object1.rr = "rr";
        object1.ss = "ss";
        object1.tt = "tt";
        object1.uu = "uu";
        object1.vv = "vv";
        object1.ww = "ww";
        object1.xx = "xx";
        object1.yy = "yy";
        object1.zz = "zzaa";


        kryo.register(ClassWithManyFields.class, new CompatibleFieldSerializer(kryo, ClassWithManyFields.class));
        roundTrip(236, 236, object1);

        CompatibleFieldSerializer serializer = new CompatibleFieldSerializer(kryo, ClassWithManyFields.class);
        serializer.removeField("bAdd");
        kryo.register(ClassWithManyFields.class, serializer);
        Object object2 = kryo.readClassAndObject(input);
        assertTrue(object2 instanceof ClassWithManyFields);
        assertNull("the bAdd field should be null", ((ClassWithManyFields) object2).bAdd);
        // update the field in order to verify the remainder of the object was deserialized correctly
        ((ClassWithManyFields) object2).bAdd = object1.bAdd;
        assertEquals(object1, object2);
    }

    public
    void testExtendedClass() throws FileNotFoundException {
        ExtendedTestClass extendedObject = new ExtendedTestClass();

        // this test would fail with DEFAULT field name strategy
        kryo.getFieldSerializerConfig()
            .setCachedFieldNameStrategy(FieldSerializer.CachedFieldNameStrategy.EXTENDED);

        CompatibleFieldSerializer serializer = new CompatibleFieldSerializer(kryo, ExtendedTestClass.class);
        kryo.register(ExtendedTestClass.class, serializer);
        roundTrip(286, 286, extendedObject);

        ExtendedTestClass object2 = (ExtendedTestClass) kryo.readClassAndObject(input);
        assertEquals(extendedObject, object2);
    }

    static public
    class TestClass {
        public String text = "something";
        public int moo = 120;
        public long moo2 = 1234120;
        public TestClass child;
        public int zzz = 123;
        public AnotherClass other;

        public
        boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TestClass other = (TestClass) obj;
            if (child == null) {
                if (other.child != null)
                    return false;
            }
            else if (!child.equals(other.child))
                return false;
            if (moo != other.moo)
                return false;
            if (moo2 != other.moo2)
                return false;
            if (text == null) {
                if (other.text != null)
                    return false;
            }
            else if (!text.equals(other.text))
                return false;
            if (zzz != other.zzz)
                return false;
            return true;
        }
    }


    static public
    class ExtendedTestClass extends TestClass {
        // keep the same names of attributes like TestClass
        public String text = "extendedSomething";
        public int moo = 127;
        public long moo2 = 5555;
        public TestClass child;
        public int zzz = 222;
        public AnotherClass other;

        public
        boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ExtendedTestClass other = (ExtendedTestClass) obj;

            if (!super.equals(obj))
                return false;
            if (child == null) {
                if (other.child != null)
                    return false;
            }
            else if (!child.equals(other.child))
                return false;
            if (moo != other.moo)
                return false;
            if (moo2 != other.moo2)
                return false;
            if (text == null) {
                if (other.text != null)
                    return false;
            }
            else if (!text.equals(other.text))
                return false;
            if (zzz != other.zzz)
                return false;
            return true;
        }
    }


    static public
    class AnotherClass {
        String value;
    }


    static public
    class ClassWithManyFields {
        public String aa;
        public String bb;
        public String bAdd;
        public String cc;
        public String dd;
        public String ee;
        public String ff;
        public String gg;
        public String hh;
        public String ii;
        public String jj;
        public String kk;
        public String ll;
        public String mm;
        public String nn;
        public String oo;
        public String pp;
        public String qq;
        public String rr;
        public String ss;
        public String tt;
        public String uu;
        public String vv;
        public String ww;
        public String xx;
        public String yy;
        public String zz;
        public String a0;
        public String b0;
        public String c0;
        public String d0;
        public String e0;
        public String f0;
        public String g0;
        public String h0;
        public String i0;
        public String j0;
        public String k0;


        @Override
        public
        boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final ClassWithManyFields that = (ClassWithManyFields) o;

            if (aa != null ? !aa.equals(that.aa) : that.aa != null) {
                return false;
            }
            if (bb != null ? !bb.equals(that.bb) : that.bb != null) {
                return false;
            }
            if (bAdd != null ? !bAdd.equals(that.bAdd) : that.bAdd != null) {
                return false;
            }
            if (cc != null ? !cc.equals(that.cc) : that.cc != null) {
                return false;
            }
            if (dd != null ? !dd.equals(that.dd) : that.dd != null) {
                return false;
            }
            if (ee != null ? !ee.equals(that.ee) : that.ee != null) {
                return false;
            }
            if (ff != null ? !ff.equals(that.ff) : that.ff != null) {
                return false;
            }
            if (gg != null ? !gg.equals(that.gg) : that.gg != null) {
                return false;
            }
            if (hh != null ? !hh.equals(that.hh) : that.hh != null) {
                return false;
            }
            if (ii != null ? !ii.equals(that.ii) : that.ii != null) {
                return false;
            }
            if (jj != null ? !jj.equals(that.jj) : that.jj != null) {
                return false;
            }
            if (kk != null ? !kk.equals(that.kk) : that.kk != null) {
                return false;
            }
            if (ll != null ? !ll.equals(that.ll) : that.ll != null) {
                return false;
            }
            if (mm != null ? !mm.equals(that.mm) : that.mm != null) {
                return false;
            }
            if (nn != null ? !nn.equals(that.nn) : that.nn != null) {
                return false;
            }
            if (oo != null ? !oo.equals(that.oo) : that.oo != null) {
                return false;
            }
            if (pp != null ? !pp.equals(that.pp) : that.pp != null) {
                return false;
            }
            if (qq != null ? !qq.equals(that.qq) : that.qq != null) {
                return false;
            }
            if (rr != null ? !rr.equals(that.rr) : that.rr != null) {
                return false;
            }
            if (ss != null ? !ss.equals(that.ss) : that.ss != null) {
                return false;
            }
            if (tt != null ? !tt.equals(that.tt) : that.tt != null) {
                return false;
            }
            if (uu != null ? !uu.equals(that.uu) : that.uu != null) {
                return false;
            }
            if (vv != null ? !vv.equals(that.vv) : that.vv != null) {
                return false;
            }
            if (ww != null ? !ww.equals(that.ww) : that.ww != null) {
                return false;
            }
            if (xx != null ? !xx.equals(that.xx) : that.xx != null) {
                return false;
            }
            if (yy != null ? !yy.equals(that.yy) : that.yy != null) {
                return false;
            }
            if (zz != null ? !zz.equals(that.zz) : that.zz != null) {
                return false;
            }
            if (a0 != null ? !a0.equals(that.a0) : that.a0 != null) {
                return false;
            }
            if (b0 != null ? !b0.equals(that.b0) : that.b0 != null) {
                return false;
            }
            if (c0 != null ? !c0.equals(that.c0) : that.c0 != null) {
                return false;
            }
            if (d0 != null ? !d0.equals(that.d0) : that.d0 != null) {
                return false;
            }
            if (e0 != null ? !e0.equals(that.e0) : that.e0 != null) {
                return false;
            }
            if (f0 != null ? !f0.equals(that.f0) : that.f0 != null) {
                return false;
            }
            if (g0 != null ? !g0.equals(that.g0) : that.g0 != null) {
                return false;
            }
            if (h0 != null ? !h0.equals(that.h0) : that.h0 != null) {
                return false;
            }
            if (i0 != null ? !i0.equals(that.i0) : that.i0 != null) {
                return false;
            }
            if (j0 != null ? !j0.equals(that.j0) : that.j0 != null) {
                return false;
            }
            return k0 != null ? k0.equals(that.k0) : that.k0 == null;
        }

        @Override
        public
        int hashCode() {
            int result = aa != null ? aa.hashCode() : 0;
            result = 31 * result + (bb != null ? bb.hashCode() : 0);
            result = 31 * result + (bAdd != null ? bAdd.hashCode() : 0);
            result = 31 * result + (cc != null ? cc.hashCode() : 0);
            result = 31 * result + (dd != null ? dd.hashCode() : 0);
            result = 31 * result + (ee != null ? ee.hashCode() : 0);
            result = 31 * result + (ff != null ? ff.hashCode() : 0);
            result = 31 * result + (gg != null ? gg.hashCode() : 0);
            result = 31 * result + (hh != null ? hh.hashCode() : 0);
            result = 31 * result + (ii != null ? ii.hashCode() : 0);
            result = 31 * result + (jj != null ? jj.hashCode() : 0);
            result = 31 * result + (kk != null ? kk.hashCode() : 0);
            result = 31 * result + (ll != null ? ll.hashCode() : 0);
            result = 31 * result + (mm != null ? mm.hashCode() : 0);
            result = 31 * result + (nn != null ? nn.hashCode() : 0);
            result = 31 * result + (oo != null ? oo.hashCode() : 0);
            result = 31 * result + (pp != null ? pp.hashCode() : 0);
            result = 31 * result + (qq != null ? qq.hashCode() : 0);
            result = 31 * result + (rr != null ? rr.hashCode() : 0);
            result = 31 * result + (ss != null ? ss.hashCode() : 0);
            result = 31 * result + (tt != null ? tt.hashCode() : 0);
            result = 31 * result + (uu != null ? uu.hashCode() : 0);
            result = 31 * result + (vv != null ? vv.hashCode() : 0);
            result = 31 * result + (ww != null ? ww.hashCode() : 0);
            result = 31 * result + (xx != null ? xx.hashCode() : 0);
            result = 31 * result + (yy != null ? yy.hashCode() : 0);
            result = 31 * result + (zz != null ? zz.hashCode() : 0);
            result = 31 * result + (a0 != null ? a0.hashCode() : 0);
            result = 31 * result + (b0 != null ? b0.hashCode() : 0);
            result = 31 * result + (c0 != null ? c0.hashCode() : 0);
            result = 31 * result + (d0 != null ? d0.hashCode() : 0);
            result = 31 * result + (e0 != null ? e0.hashCode() : 0);
            result = 31 * result + (f0 != null ? f0.hashCode() : 0);
            result = 31 * result + (g0 != null ? g0.hashCode() : 0);
            result = 31 * result + (h0 != null ? h0.hashCode() : 0);
            result = 31 * result + (i0 != null ? i0.hashCode() : 0);
            result = 31 * result + (j0 != null ? j0.hashCode() : 0);
            result = 31 * result + (k0 != null ? k0.hashCode() : 0);
            return result;
        }
    }
}
