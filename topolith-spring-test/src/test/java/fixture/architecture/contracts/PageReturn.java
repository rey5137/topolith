package fixture.architecture.contracts;

public interface PageReturn {
    org.springframework.data.domain.Page<String> load(String id);
}
