/** A module whose one contract only a {@code @Bean} method provides, so its declaration lists it (D-110). */
@TopolithModule(id = "clock", api = "io.github.rey5137.topolith.spring.boot.errorfixture.clock.api",
        provides = "io.github.rey5137.topolith.spring.boot.errorfixture.clock.api.ClockContract")
package io.github.rey5137.topolith.spring.boot.errorfixture.clock;

import io.github.rey5137.topolith.annotation.TopolithModule;
