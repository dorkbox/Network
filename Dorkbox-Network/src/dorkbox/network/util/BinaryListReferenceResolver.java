package dorkbox.network.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Util;

public class BinaryListReferenceResolver implements com.esotericsoftware.kryo.ReferenceResolver {
    protected final List<Object> readObjects  = new ArrayList<Object>();

    private int[]     writtenObjectsHashes    = new int[10];
    // objects, then values
    private Object[]  writtenObjectsAndValues = new Object[20];

    private int size = 0;
    private int primaryArraySize = 0;

    @Override
    public void setKryo(Kryo kryo) {
    }

    private static int binarySearch(int[] array, int startIndex, int endIndex, int value) {
        int low = startIndex, mid = -1, high = endIndex - 1;
        while (low <= high) {
            mid = low + high >>> 1;
            if (value > array[mid]) {
                low = mid + 1;
            } else if (value == array[mid]) {
                return mid;
            } else {
                high = mid - 1;
            }
        }
        if (mid < 0) {
            int insertPoint = endIndex;
            for (int index = startIndex; index < endIndex; index++) {
                if (value < array[index]) {
                    insertPoint = index;
                }
            }
            return -insertPoint - 1;
        }
        return -mid - (value < array[mid] ? 1 : 2);
    }

    @Override
    public int addWrittenObject(Object object) {
        int id = size;
        int hash = System.identityHashCode(object);
        int idx = binarySearch(writtenObjectsHashes, 0, primaryArraySize, hash);
        if (idx < 0) {
            idx = -(idx + 1);
            if (primaryArraySize == writtenObjectsHashes.length) {
                int[] newHashArray = new int[writtenObjectsHashes.length * 3 / 2];
                System.arraycopy(writtenObjectsHashes, 0, newHashArray, 0, writtenObjectsHashes.length);
                writtenObjectsHashes = newHashArray;
                Object[] newObjectArray = new Object[newHashArray.length * 2];
                System.arraycopy(writtenObjectsAndValues, 0, newObjectArray, 0, writtenObjectsAndValues.length);
                writtenObjectsAndValues = newObjectArray;
            }
            for (int i = writtenObjectsHashes.length - 1; i > idx; i--) {
                int j = 2 * i;
                writtenObjectsHashes[i] = writtenObjectsHashes[i - 1];
                writtenObjectsAndValues[j] = writtenObjectsAndValues[j - 2];
                writtenObjectsAndValues[j + 1] = writtenObjectsAndValues[j - 1];
            }
            writtenObjectsHashes[idx] = hash;
            writtenObjectsAndValues[2 * idx] = object;
            writtenObjectsAndValues[2 * idx + 1] = id;
            primaryArraySize++;
            size++;
            return id;
        } else {
            idx = 2 * idx; // objects and values array has bigger indexes
            if (writtenObjectsAndValues[idx + 1] instanceof Integer) {
                // single slot
                if (writtenObjectsAndValues[idx] == object) {
                    return (Integer) writtenObjectsAndValues[idx + 1];
                } else {
                    Object[] keys = new Object[4];
                    int[] values = new int[4];
                    keys[0] = writtenObjectsAndValues[idx];
                    values[0] = (Integer) writtenObjectsAndValues[idx + 1];
                    keys[1] = object;
                    values[1] = id;
                    writtenObjectsAndValues[idx] = keys;
                    writtenObjectsAndValues[idx + 1] = values;
                    size++;
                    return id;
                }
            } else {
                // multiple entry slot
                Object[] keys = (Object[]) writtenObjectsAndValues[idx];
                for (int i = 0; i < keys.length; i++) {
                    if (keys[i] == object) {
                        return ((int[]) writtenObjectsAndValues[idx + 1])[i];
                    }
                    if (keys[i] == null) {
                        keys[i] = object;
                        ((int[]) writtenObjectsAndValues[idx + 1])[i] = id;
                        size++;
                        return id;
                    }
                }
                // expand
                Object[] newKeys = new Object[keys.length * 3 / 2];
                System.arraycopy(keys, 0, newKeys, 0, keys.length);
                newKeys[keys.length] = object;
                int[] newValues = new int[keys.length * 3 / 2];
                System.arraycopy(writtenObjectsAndValues[idx + 1], 0, newValues, 0, keys.length);
                writtenObjectsAndValues[idx] = newKeys;
                writtenObjectsAndValues[idx + 1] = newValues;
                size++;
                return id;
            }

        }
    }

    @Override
    public int getWrittenId(Object object) {
        int hash = System.identityHashCode(object);
        int idx = binarySearch(writtenObjectsHashes, 0, primaryArraySize, hash);
        if (idx < 0) {
            return -1;
        } else {
            idx = 2 * idx; // objects and values array has bigger indexes
            if (writtenObjectsAndValues[idx + 1] instanceof Integer) {
                // single slot
                if (writtenObjectsAndValues[idx] == object) {
                    return (Integer) writtenObjectsAndValues[idx + 1];
                } else {
                    return -1;
                }
            } else {
                // multiple entry slot
                Object[] keys = (Object[]) writtenObjectsAndValues[idx];
                for (int i = 0; i < keys.length; i++) {
                    if (keys[i] == object) {
                        return ((int[]) writtenObjectsAndValues[idx + 1])[i];
                    }
                    if (keys[i] == null) {
                        return -1;
                    }
                }
                return -1;
            }
        }

    }

    @Override
    @SuppressWarnings("rawtypes")
    public int nextReadId(Class type) {
        int id = readObjects.size();
        readObjects.add(null);
        return id;
    }

    @Override
    public void setReadObject(int id, Object object) {
        readObjects.set(id, object);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Object getReadObject(Class type, int id) {
        return readObjects.get(id);
    }

    @Override
    public void reset() {
        readObjects.clear();
        size = 0;
        primaryArraySize = 0;
        writtenObjectsAndValues = new Object[20];
        writtenObjectsHashes = new int[10];
    }

    /** Returns false for all primitive wrappers. */
    @Override
    @SuppressWarnings("rawtypes")
    public boolean useReferences(Class type) {
        return !Util.isWrapperClass(type) &&
                !type.equals(String.class) &&
                !type.equals(Date.class) &&
                !type.equals(BigDecimal.class) &&
                !type.equals(BigInteger.class);
    }

    public void addReadObject(int id, Object object) {
        while (id >= readObjects.size()) {
            readObjects.add(null);
        }
        readObjects.set(id, object);
    }
}