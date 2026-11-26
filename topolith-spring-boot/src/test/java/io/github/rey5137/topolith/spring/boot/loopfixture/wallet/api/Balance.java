package io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api;

import java.util.ArrayList;
import java.util.List;

/** A mutable argument and result, so a test can see whether a call shares objects. */
public class Balance {
    private long amount;
    private List<String> notes = new ArrayList<>();

    public Balance() {
    }

    public Balance(long amount) {
        this.amount = amount;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes;
    }
}
