package symtable.type;

/**
 * @author WFS
 * @date 2022/12/17 11:50
 */
public class ArrayType implements Type {
    Object count;
    Type subType;

    public ArrayType(Object count, Type subType) {
        this.count = count;
        this.subType = subType;
    }

    public Object getCount() {
        return count;
    }

    public Type getSubType() {
        return subType;
    }

    @Override
    public String toString() {
        StringBuilder typeStr = new StringBuilder();
        if (count instanceof Integer && (int)count == 0) {
            return typeStr.append(subType).toString();
        }
        return typeStr.append("array(")
                .append(count)
                .append(",")
                .append(subType)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof ArrayType) {
            return this.subType.equals(((ArrayType) o).getSubType());
        }
        return false;
    }
}
