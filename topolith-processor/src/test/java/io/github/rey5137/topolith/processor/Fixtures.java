package io.github.rey5137.topolith.processor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Source trees for the processor tests: a user API (package {@code com.acme.user}) and its module. */
final class Fixtures {
    private Fixtures() {
    }

    /** The API declaration plus every interface kind the contract rule must sort. */
    static Map<String, String> api(String declaration, String... extra) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/acme/user/package-info.java", declaration + "\npackage com.acme.user;\n"
                + "import io.github.rey5137.topolith.annotation.TopolithModule;\n");
        sources.put("com/acme/user/Crud.java", "package com.acme.user;\npublic interface Crud<T> { T find(); }\n");
        sources.put("com/acme/user/UserService.java",
                "package com.acme.user;\npublic interface UserService extends Crud<String> { void rename(); }\n");
        sources.put("com/acme/user/Events.java", """
                package com.acme.user;
                public class Events { public interface Listener { void on(); } }
                """);
        sources.put("com/acme/user/Shape.java",
                "package com.acme.user;\npublic sealed interface Shape permits Circle { }\n");
        sources.put("com/acme/user/Circle.java",
                "package com.acme.user;\npublic record Circle() implements Shape { }\n");
        sources.put("com/acme/user/Marker.java", "package com.acme.user;\npublic @interface Marker { }\n");
        sources.put("com/acme/user/Hidden.java", "package com.acme.user;\ninterface Hidden { }\n");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            sources.put(extra[i], extra[i + 1]);
        }
        return sources;
    }

    static final String API = "@TopolithModule(id = \"user\", api = \"com.acme.user\")";

    /** The module side: its declaration in {@code com.acme.user.impl} and implementers of the user contracts. */
    static Map<String, String> module(String declaration, String... extra) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/acme/user/impl/package-info.java", declaration + "\npackage com.acme.user.impl;\n"
                + "import io.github.rey5137.topolith.annotation.TopolithModule;\n");
        sources.put("com/acme/user/impl/UserServiceImpl.java", """
                package com.acme.user.impl;
                import com.acme.user.UserService;
                class UserServiceImpl implements UserService {
                    public String find() { return "found"; }
                    public void rename() { }
                }
                """);
        sources.put("com/acme/user/impl/ListenerImpl.java", """
                package com.acme.user.impl;
                import com.acme.user.Events;
                class ListenerImpl implements Events.Listener {
                    public void on() { }
                }
                """);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            sources.put(extra[i], extra[i + 1]);
        }
        return sources;
    }

    static final String MODULE = "@TopolithModule(id = \"user\", api = \"com.acme.user\")";
}
