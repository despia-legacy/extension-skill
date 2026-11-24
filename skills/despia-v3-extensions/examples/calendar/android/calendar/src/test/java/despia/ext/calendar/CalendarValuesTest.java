package despia.ext.calendar;
import org.junit.Test;
import org.json.JSONObject;
import java.lang.reflect.*;
import static org.junit.Assert.*;
public class CalendarValuesTest {
    private Object invoke(String name, Class<?> type, Object value) throws Exception {
        Method method=CalendarExtension.class.getDeclaredMethod(name,type);method.setAccessible(true);return method.invoke(null,value);
    }
    private void rejectInteger(Object value) throws Exception {
        try { invoke("integer",Object.class,value);fail("invalid integer accepted"); }
        catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalArgumentException);}
    }
    @Test public void calendarRowsUseSharedFieldsAndKeepNativeMetadata() throws Exception {
        JSONObject nativeRow = new JSONObject("{\"_id\":7,\"calendar_displayName\":\"Local planning\",\"calendar_access_level\":700,\"account_type\":\"LOCAL\"}");
        JSONObject calendar = (JSONObject) invoke("normalizedCalendar", JSONObject.class, nativeRow);
        assertEquals("7", calendar.getString("id"));
        assertEquals("Local planning", calendar.getString("title"));
        assertTrue(calendar.getBoolean("writable"));
        assertEquals("LOCAL", calendar.getJSONObject("android").getString("account_type"));
        nativeRow.put("calendar_access_level", 500);
        assertTrue(((JSONObject) invoke("normalizedCalendar", JSONObject.class, nativeRow)).getBoolean("writable"));
        nativeRow.put("calendar_access_level", 200);
        assertFalse(((JSONObject) invoke("normalizedCalendar", JSONObject.class, nativeRow)).getBoolean("writable"));
    }
    @Test public void exactIntegerValues() throws Exception {
        assertEquals(1700000000000L,invoke("integer",Object.class,1700000000000L));
        assertEquals(42L,invoke("integer",Object.class,"42"));
        for(Object value:new Object[]{1.5,Double.NaN,Double.POSITIVE_INFINITY,"not-an-id",true})rejectInteger(value);
    }
    @Test public void recurrenceKeepsPlatformFields() throws Exception {
        JSONObject rule=new JSONObject("{\"frequency\":2,\"interval\":1,\"days_of_week\":[{\"day\":2,\"week\":-1}],\"end_count\":6}");
        assertEquals("FREQ=MONTHLY;INTERVAL=1;BYDAY=-1MO;COUNT=6",invoke("recurrence",JSONObject.class,rule));
        JSONObject until=new JSONObject("{\"frequency\":0,\"interval\":2,\"end_ms\":0}");
        assertEquals("FREQ=DAILY;INTERVAL=2;UNTIL=19700101T000000Z",invoke("recurrence",JSONObject.class,until));
    }
    @Test public void invalidRecurrenceIsRejected() throws Exception {
        for(String text:new String[]{"{\"frequency\":9,\"interval\":1}","{\"frequency\":0,\"interval\":0}","{\"frequency\":1,\"interval\":1,\"days_of_week\":[{\"day\":8}]}"}) {
            try { invoke("recurrence",JSONObject.class,new JSONObject(text));fail("invalid recurrence accepted"); }
            catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalArgumentException);}
        }
    }
}
