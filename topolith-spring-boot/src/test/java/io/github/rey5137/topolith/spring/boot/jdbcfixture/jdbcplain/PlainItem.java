package io.github.rey5137.topolith.spring.boot.jdbcfixture.jdbcplain;

import org.springframework.data.annotation.Id;

public class PlainItem {
    @Id
    private Long id;
    private String name;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
