package dev.immich.media;

import android.database.AbstractCursor;

import java.util.List;

/**
 * In-memory cursor whose accessors coerce between numeric types.
 *
 * <p>{@code MatrixCursor} casts with {@code (Integer) get(column)}, so a value stored as a
 * {@code Long} blows up when an app calls {@code getInt} on it. Real MediaStore cursors are
 * window backed and coerce through {@code Number}, which is what this class reproduces.
 */
final class CoercingCursor extends AbstractCursor {

    private final String[] columns;
    private final List<Object[]> rows;

    CoercingCursor(String[] columns, List<Object[]> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public String[] getColumnNames() {
        return columns;
    }

    private Object value(int column) {
        if (mPos < 0 || mPos >= rows.size()) {
            return null;
        }
        Object[] row = rows.get(mPos);
        if (column < 0 || column >= row.length) {
            return null;
        }
        return row[column];
    }

    private double number(int column) {
        Object value = value(column);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0d;
    }

    @Override
    public String getString(int column) {
        Object value = value(column);
        return value == null ? null : value.toString();
    }

    @Override
    public short getShort(int column) {
        return (short) number(column);
    }

    @Override
    public int getInt(int column) {
        return (int) number(column);
    }

    @Override
    public long getLong(int column) {
        return (long) number(column);
    }

    @Override
    public float getFloat(int column) {
        return (float) number(column);
    }

    @Override
    public double getDouble(int column) {
        return number(column);
    }

    @Override
    public byte[] getBlob(int column) {
        Object value = value(column);
        return value instanceof byte[] ? (byte[]) value : null;
    }

    @Override
    public boolean isNull(int column) {
        return value(column) == null;
    }

    @Override
    public int getType(int column) {
        Object value = value(column);
        if (value == null) {
            return FIELD_TYPE_NULL;
        }
        if (value instanceof byte[]) {
            return FIELD_TYPE_BLOB;
        }
        if (value instanceof Float || value instanceof Double) {
            return FIELD_TYPE_FLOAT;
        }
        if (value instanceof Number) {
            return FIELD_TYPE_INTEGER;
        }
        return FIELD_TYPE_STRING;
    }
}
