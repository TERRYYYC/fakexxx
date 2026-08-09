package name.caiyao.fakegps.data.repository;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import kotlin.jvm.functions.Function1;
import name.caiyao.fakegps.config.ConfigPrefsSync;
import org.junit.Test;

/** Compiled contract: an editor save must name the profile that becomes published. */
public class ProfilePublicationSelectionContractTest {

    @Test
    public void repositoryPublisherReceivesTheSavedProfileId() {
        boolean hasProfileAwarePublisher = Arrays.stream(ProfileRepository.class.getDeclaredConstructors())
                .map(Constructor::getParameterTypes)
                .anyMatch(types -> Arrays.asList(types).contains(Function1.class));

        assertTrue("publisher callback must receive the selected profile id", hasProfileAwarePublisher);
    }

    @Test
    public void configPublisherAcceptsAnExplicitProfileId() {
        boolean hasProfileAwareSync = Arrays.stream(ConfigPrefsSync.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("sync"))
                .map(Method::getParameterTypes)
                .anyMatch(types -> Arrays.equals(types, new Class<?>[] {Context.class, Long.class}));

        assertTrue("sync must publish the selected profile instead of the oldest row", hasProfileAwareSync);
    }
}
