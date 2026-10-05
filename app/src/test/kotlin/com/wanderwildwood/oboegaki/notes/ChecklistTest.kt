package com.wanderwildwood.oboegaki.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTest {

    private val list = "# Groceries\n- [ ] milk\n  - [x] oat\n* [ ] bread\nnot a task\n"

    @Test
    fun readsTasksAndText() {
        val parsed = lines(list)
        assertEquals(Line.Text(0, "# Groceries"), parsed[0])
        assertEquals(Line.Task(1, "", false, "milk"), parsed[1])
        assertEquals(Line.Task(2, "  ", true, "oat"), parsed[2])
        assertEquals(Line.Task(3, "", false, "bread"), parsed[3])
        assertEquals(Line.Text(4, "not a task"), parsed[4])
        assertTrue(hasTasks(list))
        assertFalse(hasTasks("just words\n- a bullet"))
    }

    @Test
    fun tickingChangesOnlyTheMark() {
        assertEquals("# Groceries\n- [x] milk\n  - [x] oat\n* [ ] bread\nnot a task\n", toggle(list, 1))
        assertEquals("# Groceries\n- [ ] milk\n  - [ ] oat\n* [ ] bread\nnot a task\n", toggle(list, 2))
        assertEquals(list, toggle(list, 4))
        assertEquals(list, toggle(list, 99))
    }

    @Test
    fun aTaskWithBracketsInItsTextTicksTheRightOne() {
        assertEquals("- [x] buy [the] thing", toggle("- [ ] buy [the] thing", 0))
    }

    @Test
    fun aNewTaskGoesAfterTheLastTask() {
        assertEquals(
            "# Groceries\n- [ ] milk\n  - [x] oat\n* [ ] bread\n- [ ] eggs\nnot a task\n",
            addTask(list, "eggs"),
        )
        assertEquals("- [ ] eggs\n", addTask("", "eggs"))
        assertEquals("Shop\n- [ ] eggs\n", addTask("Shop\n", " eggs "))
        assertEquals("Shop\n- [ ] eggs", addTask("Shop", "eggs"))
    }

    @Test
    fun aLineBecomesATaskAndBack() {
        val (made, moved) = toggleTaskLine("Shop\nmilk\nbread", 7)
        assertEquals("Shop\n- [ ] milk\nbread", made)
        assertEquals(6, moved)
        val (back, movedBack) = toggleTaskLine(made, 12)
        assertEquals("Shop\nmilk\nbread", back)
        assertEquals(-6, movedBack)
        assertEquals("- [ ] milk" to 4, toggleTaskLine("- milk", 3))
    }

    @Test
    fun anEmptyLineAtTheCursorBecomesAnEmptyTask() {
        assertEquals("Shop\n- [ ] " to 6, toggleTaskLine("Shop\n", 5))
        assertEquals("- [ ] " to 6, toggleTaskLine("", 0))
    }

    @Test
    fun previewsDropMarkdownMarks() {
        assertEquals("Robin Wall Kimmerer", plain("> *Robin Wall Kimmerer*"))
        assertEquals("BRAIDING SWEETGRASS", plain("> > BRAIDING SWEETGRASS"))
        assertEquals("a bold word", plain("- a **bold** word"))
        assertEquals("snake_case_name stays", plain("snake_case_name stays"))
        assertEquals("milk · bread", preview("# Shop\n- [ ] milk\n- [x] bread\n"))
        assertEquals("ask not", preview("![[Voice 0200.m4a]]\n\nask not\n"))
    }

    @Test
    fun embedsAndRecordings() {
        assertEquals(listOf("Voice 0200.m4a", "scan.pdf"), embeds("![[Voice 0200.m4a]]\ntext ![[scan.pdf|a page]]"))
        assertEquals("Voice 0200.m4a", recordingOn("  ![[Voice 0200.m4a]] "))
        assertEquals(null, recordingOn("![[scan.pdf]]"))
        assertEquals(null, recordingOn("see ![[Voice 0200.m4a]] here"))
    }
}
