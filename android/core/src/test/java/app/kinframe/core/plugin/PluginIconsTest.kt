package app.kinframe.core.plugin

import app.kinframe.core.design.PluginIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PluginIconsTest {
    @Test fun everyApiIconIsDrawn() {
        PluginIconNames.all.forEach { name -> assertNotNull(name, PluginIcons.of(name)) }
        assertNull(PluginIcons.of("rocket"))
        assertEquals("names are unique", PluginIconNames.all.size, PluginIconNames.all.toSet().size)
    }
}
