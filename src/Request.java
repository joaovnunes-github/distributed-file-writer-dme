package src;

public class Request implements Comparable<Request> {

    public final int timestamp;

    public final int processID;

    public Request(int timestamp, int processID) {
        this.timestamp = timestamp;
        this.processID = processID;
    }

    @Override
    public int compareTo(Request other) {
        if(this.timestamp != other.timestamp) {
            return this.timestamp - other.timestamp;
        }
        return this.processID - other.processID;
    }

    @Override
    public boolean equals(Object obj) {
        if(this == obj) {return true;}
        if(obj == null || getClass() != obj.getClass()) {return false;}
        Request request = (Request)obj;
        return timestamp == request.timestamp && processID == request.processID;
    }

    @Override
    public int hashCode() {
        return timestamp * 31 + processID;
    }
}

