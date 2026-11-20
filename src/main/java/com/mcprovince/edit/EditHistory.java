package com.mcprovince.edit;

import com.mcprovince.core.Lang;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 通用的撤销 / 重做栈。
 *
 * <p>每个条目同时带着「正向操作」和「反向操作」，所以撤销、重做都只是把对应那一半
 * 交给调用方去执行。类型参数是操作本身的描述（在本 mod 里就是一条待发给服务端的编辑请求）。</p>
 */
public final class EditHistory<T> {

    /**
     * 一步可撤销的操作。
     *
     * <p>描述同时存两份：{@code label} 是记下这一步时拼好的整句，
     * {@code labelKey}/{@code labelArgs} 是它的文案键与参数——界面语言换掉以后
     * 用 {@link #display()} 现算，撤销提示不会卡在旧语言上。</p>
     */
    public record Entry<T>(String label, String labelKey, Object[] labelArgs, T forward, T backward) {

        public Entry(String label, T forward, T backward) {
            this(label, null, null, forward, backward);
        }

        public String display() {
            return labelKey == null ? label : Lang.t(labelKey, labelArgs);
        }
    }

    /** 默认保留多少步。 */
    public static final int DEFAULT_LIMIT = 64;

    private final Deque<Entry<T>> undoStack = new ArrayDeque<>();
    private final Deque<Entry<T>> redoStack = new ArrayDeque<>();
    private final int limit;

    public EditHistory() {
        this(DEFAULT_LIMIT);
    }

    public EditHistory(int limit) {
        this.limit = Math.max(1, limit);
    }

    /** 记下一步刚刚做完的操作。 */
    public void push(Entry<T> entry) {
        this.undoStack.push(entry);
        while (this.undoStack.size() > this.limit) {
            this.undoStack.removeLast();
        }
        this.redoStack.clear();
    }

    /**
     * 取出一步要撤销的操作，并把它挪到重做栈。
     *
     * @return 该条目；没有可撤销的就返回 null
     */
    public Entry<T> popUndo() {
        if (this.undoStack.isEmpty()) {
            return null;
        }
        Entry<T> entry = this.undoStack.pop();
        this.redoStack.push(entry);
        return entry;
    }

    /**
     * 取出一步要重做的操作，并把它挪回撤销栈。
     */
    public Entry<T> popRedo() {
        if (this.redoStack.isEmpty()) {
            return null;
        }
        Entry<T> entry = this.redoStack.pop();
        this.undoStack.push(entry);
        return entry;
    }

    public boolean canUndo() {
        return !this.undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !this.redoStack.isEmpty();
    }

    public int undoDepth() {
        return this.undoStack.size();
    }

    public void clear() {
        this.undoStack.clear();
        this.redoStack.clear();
    }
}
