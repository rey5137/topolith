package fixture.architecture.contracts;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;

import java.util.List;
import java.util.Map;

public interface ValueContract {
    record Line(String sku, int quantity) {
    }

    List<Line> lines(String order, long limit) throws ModuleUnavailableException;

    Map<String, List<Line>> grouped(int[] ids);
}
