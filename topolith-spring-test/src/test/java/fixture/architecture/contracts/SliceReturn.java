package fixture.architecture.contracts;

public interface SliceReturn {
    org.springframework.data.domain.Slice<String> load(String id);
}
