package com.lego.namnv.core.message;

public abstract class AbstractMessage<BodyType> implements LegoMessage<BodyType> {

    @Override
    public String toString() {
        var sb = new StringBuilder() //
            .append("------\nTYPE: \n\t") //
            .append(getClass().getName()) //
            .append("\nHEADER:\n\t");

        for (var h : getHeaders())
            sb.append("\t").append(h.getKey()).append(": ").append(h.getValue());

        sb.append("\nBODY:\n\n").append(getBody());
        return sb.append("\n------").toString();
    }
}
