package dev.flyboot.core;

import dev.flyboot.core.sample.Clock;
import dev.flyboot.core.sample.NotAComponent;
import dev.flyboot.core.sample.PetRepository;
import dev.flyboot.core.sample.PetService;
import dev.flyboot.core.sample.Service;
import dev.flyboot.core.sample.ambiguous.Greeter;
import dev.flyboot.core.sample.ambiguous.Hi;
import dev.flyboot.core.sample.cycle.A;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlyContextTest {

    @Test
    void scanFindsComponentsThroughMetaAnnotationsOnly() {
        List<Class<?>> found = FlyContext.scan(getClass().getClassLoader(), "dev.flyboot.core.sample");
        assertTrue(found.contains(PetRepository.class));
        assertTrue(found.contains(PetService.class));
        assertFalse(found.contains(NotAComponent.class));
        assertFalse(found.contains(Service.class), "annotations themselves are not components");
    }

    @Test
    void injectsConstructorsWithComponentsAndRegisteredObjects() {
        FlyContext context = new FlyContext()
                .registerComponent(PetService.class)
                .registerComponent(PetRepository.class)
                .registerSingleton(new Clock(42))
                .refresh();
        PetService service = context.getBean(PetService.class);
        assertSame(context.getBean(PetRepository.class), service.repository());
        assertEquals(42, service.clock().now());
        assertEquals(List.of(service), context.getBeansWithAnnotation(Service.class));
        assertEquals(2, context.getBeansWithAnnotation(Component.class).size());
    }

    @Test
    void reportsWhatIsMissingCircularOrAmbiguous() {
        FlyContext missing = new FlyContext().registerComponent(PetService.class).registerComponent(PetRepository.class);
        assertTrue(assertThrows(FlyContextException.class, missing::refresh).getMessage().contains("Clock"));

        FlyContext cycle = FlyContext.ofPackages("dev.flyboot.core.sample.cycle");
        assertTrue(assertThrows(FlyContextException.class, cycle::refresh).getMessage().contains("Circular dependency"));

        FlyContext two = FlyContext.ofPackages("dev.flyboot.core.sample.ambiguous").refresh();
        assertThrows(FlyContextException.class, () -> two.getBean(Greeter.class));
        assertEquals(Hi.class, two.getBean(Hi.class).getClass(), "the @Autowired constructor is used");
    }

    @Test
    void refreshedContextIsClosedForRegistration() {
        FlyContext context = new FlyContext().refresh();
        assertThrows(IllegalStateException.class, () -> context.registerComponent(PetRepository.class));
    }
}
