package com.facebook.presto.block;

import com.facebook.presto.Tuple;
import com.facebook.presto.TupleInfo;
import com.facebook.presto.block.Cursor.AdvanceResult;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Longs;

import java.util.NoSuchElementException;

import static com.facebook.presto.block.Cursor.AdvanceResult.FINISHED;
import static com.facebook.presto.block.Cursor.AdvanceResult.MUST_YIELD;
import static com.facebook.presto.block.Cursor.AdvanceResult.SUCCESS;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class Cursors
{
    public static boolean advanceNextPositionNoYield(Cursor cursor)
    {
        AdvanceResult result = cursor.advanceNextPosition();
        if (result == MUST_YIELD) {
            throw new IllegalStateException("Cursor must yield but support yield is not supported here");
        }
        return result != FINISHED;
    }

    public static boolean advanceNextValueNoYield(Cursor cursor)
    {
        AdvanceResult result = cursor.advanceNextValue();
        if (result == MUST_YIELD) {
            throw new IllegalStateException("Cursor requested caller to yield but yield is not supported here");
        }
        return result != FINISHED;
    }

    public static boolean advanceToPositionNoYield(Cursor cursor, long position)
    {
        AdvanceResult result = cursor.advanceToPosition(position);
        if (result == MUST_YIELD) {
            throw new IllegalStateException("Cursor requested caller to yield but yield is not supported here");
        }
        return result != FINISHED;
    }

    /**
     * Advances all cursors to the next position

     * @return true if all cursors were advanced. Otherwise, false.
     */
    public static boolean advanceNextPositionNoYield(Cursor... cursors)
    {
        return advanceNextPositionNoYield(ImmutableList.copyOf(cursors));
    }

    public static boolean advanceNextPositionNoYield(Iterable<Cursor> cursors)
    {
        boolean advancedAll = true;
        for (Cursor cursor : cursors) {
            advancedAll = advanceNextPositionNoYield(cursor) && advancedAll;
        }

        return advancedAll;
    }

    public static AdvanceResult advanceToPositionByValues(Cursor cursor, long position)
    {
        checkNotNull(cursor, "cursor is null");
        checkArgument(position >= 0, "position must be at least zero");

        if (cursor.isFinished()) {
            return FINISHED;
        }

        // Initialize to the first position if necessary
        if (isInitialized(cursor)) {
            checkArgument(position >= cursor.getPosition(), "must advance forward");
        }
        else {
            AdvanceResult advanceResult = cursor.advanceNextPosition();
            if (advanceResult != SUCCESS) {
                return advanceResult;
            }
        }

        // Skip by values
        while (cursor.getCurrentValueEndPosition() < position) {
            AdvanceResult advanceResult = cursor.advanceNextValue();
            if (advanceResult != SUCCESS) {
                return advanceResult;
            }
        }

        // Skip by positions
        while(cursor.getPosition() < position) {
            AdvanceResult advanceResult = cursor.advanceNextPosition();
            if (advanceResult != SUCCESS) {
                return advanceResult;
            }
        }

        return SUCCESS;
    }

    /**
     * Cursor is initialized after it has been advanced successfully at least once
     */
    public static boolean isInitialized(Cursor cursor)
    {
        checkNotNull(cursor, "cursor is null");
        return cursor.isValid() || cursor.isFinished();
    }

    public static Ordering<Cursor> orderByPosition()
    {
        return new Ordering<Cursor>()
        {
            @Override
            public int compare(Cursor left, Cursor right)
            {
                return Longs.compare(left.getPosition(), right.getPosition());
            }
        };
    }

    public static Predicate<Cursor> isFinished()
    {
        return new Predicate<Cursor>()
        {
            @Override
            public boolean apply(Cursor input)
            {
                return input.isFinished();
            }
        };
    }

    public static TupleStreamPosition asTupleStreamPosition(Cursor cursor)
    {
        return new TupleStreamPosition(cursor);
    }

    public static void appendCurrentTupleToBlockBuilder(Cursor cursor, BlockBuilder blockBuilder)
    {
        checkNotNull(cursor, "cursor is null");
        checkNotNull(blockBuilder, "blockBuilder is null");
        for (int column = 0; column < cursor.getTupleInfo().getFieldCount(); column++) {
            appendCurrentTupleFieldToBlockBuilder(cursor, column, blockBuilder);
        }
    }

    public static void appendCurrentTupleToTupleBuilder(Cursor cursor, TupleInfo.Builder tupleBuilder)
    {
        checkNotNull(cursor, "cursor is null");
        checkNotNull(tupleBuilder, "tupleBuilder is null");
        for (int column = 0; column < cursor.getTupleInfo().getFieldCount(); column++) {
            appendCurrentTupleFieldToTupleBuilder(cursor, column, tupleBuilder);
        }
    }

    public static void appendCurrentTupleFieldToBlockBuilder(Cursor cursor, int field, BlockBuilder blockBuilder)
    {
        checkNotNull(cursor, "cursor is null");
        checkNotNull(blockBuilder, "blockBuilder is null");
        TupleInfo.Type type = cursor.getTupleInfo().getTypes().get(field);
        switch (type) {
            case FIXED_INT_64:
                blockBuilder.append(cursor.getLong(field));
                break;
            case DOUBLE:
                blockBuilder.append(cursor.getDouble(field));
                break;
            case VARIABLE_BINARY:
                blockBuilder.append(cursor.getSlice(field));
                break;
            default:
                throw new AssertionError("Unknown type: " + type);
        }
    }

    public static void appendCurrentTupleFieldToTupleBuilder(Cursor cursor, int field, TupleInfo.Builder tupleBuilder)
    {
        checkNotNull(cursor, "cursor is null");
        checkNotNull(tupleBuilder, "tupleBuilder is null");
        TupleInfo.Type type = cursor.getTupleInfo().getTypes().get(field);
        switch (type) {
            case FIXED_INT_64:
                tupleBuilder.append(cursor.getLong(field));
                break;
            case DOUBLE:
                tupleBuilder.append(cursor.getDouble(field));
                break;
            case VARIABLE_BINARY:
                tupleBuilder.append(cursor.getSlice(field));
                break;
            default:
                throw new AssertionError("Unknown type: " + type);
        }
    }

    public static boolean currentTupleFieldEquals(Cursor cursor, Tuple tuple, int field)
    {
        checkNotNull(cursor, "cursor is null");
        checkNotNull(tuple, "tuple is null");
        checkArgument(field >= 0, "field must be greater than or equal to zero");
        TupleInfo.Type type = tuple.getTupleInfo().getTypes().get(field);
        if (!type.equals(cursor.getTupleInfo().getTypes().get(field))) {
            // Type mismatch
            return false;
        }
        switch (type) {
            case FIXED_INT_64:
                return tuple.getLong(field) == cursor.getLong(field);
            case DOUBLE:
                return tuple.getDouble(field) == cursor.getDouble(field);
            case VARIABLE_BINARY:
                return tuple.getSlice(field).equals(cursor.getSlice(field));
            default:
                throw new AssertionError("Unknown type: " + type);
        }
    }

    /**
     * Generic implementation of currentTupleEquals that is fairly efficient in most case.
     * TODO: this can probably be used to replace a lot of the lesser efficient currentTupleEquals() implementations in some cursors
     */
    public static boolean currentTupleEquals(Cursor cursor, Tuple tuple)
    {
        checkNotNull(cursor, "cursor is null");
        checkNotNull(tuple, "tuple is null");
        if (cursor.getTupleInfo().getFieldCount() != tuple.getTupleInfo().getFieldCount()) {
            return false;
        }
        for (int field = 0; field < cursor.getTupleInfo().getFieldCount(); field++) {
            if (!currentTupleFieldEquals(cursor, tuple, field)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validate whether the given cursor is currently at a readable position
     */
    public static void checkReadablePosition(Cursor cursor)
    {
        if (cursor.isFinished()) {
            throw new NoSuchElementException("already finished");
        }
        checkState(cursor.isValid(), "cursor not yet advanced");
    }
}
