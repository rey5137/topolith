package fixture.invoice.api;

public interface Store<T> {
    void put(T value);

    T last();
}
