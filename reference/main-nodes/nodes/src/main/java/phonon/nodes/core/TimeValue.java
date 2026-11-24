package phonon.nodes.core;
import java.math.*;
import java.util.regex.*;

final class TimeValue {
    private static final Pattern PART=Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([dhms])",Pattern.CASE_INSENSITIVE);
    static long millis(Object input){
        if(input instanceof Number n){long value=n.longValue();if(value<0||!new BigDecimal(n.toString()).equals(new BigDecimal(Long.toString(value)))){
            if(value<0||new BigDecimal(n.toString()).compareTo(BigDecimal.valueOf(value))!=0)throw new IllegalArgumentException("Duration must be a nonnegative whole millisecond count.");
        }return value;}
        String s=String.valueOf(input).trim().toLowerCase();if(s.matches("[0-9]+"))return Long.parseLong(s);
        Matcher m=PART.matcher(s);int end=0;BigDecimal total=BigDecimal.ZERO;
        while(m.find()){if(m.start()!=end)throw new IllegalArgumentException("Invalid duration: "+s);end=m.end();long scale=switch(m.group(2)){case "d"->86400000L;case "h"->3600000L;case "m"->60000L;default->1000L;};total=total.add(new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(scale)));}
        if(end!=s.length()||end==0)throw new IllegalArgumentException("Invalid duration: "+s);return total.longValueExact();
    }
}
