package org.jboss.pnc.artsync.pnc;

import jakarta.ws.rs.core.Response;

import java.io.File;


public sealed interface Result<T> permits Result.Error, Result.Success {
    record Success<T>(T result) implements Result<T> {}

    sealed interface Error extends Result permits Error.ClientError, Error.ServerError, Error.UncaughtException {
        sealed interface ClientError extends Result.Error permits ClientError.AuthorizationError, ClientError.ClientTimeout, ClientError.NotFound, ClientError.SSLError, ClientError.ServerUnreachable {
            record ServerUnreachable() implements Result.Error.ClientError {}
            record ClientTimeout() implements Result.Error.ClientError {}
            record AuthorizationError() implements Result.Error.ClientError {}
            record NotFound(String uri) implements Result.Error.ClientError {}
            record SSLError(String message) implements Result.Error.ClientError {}
        }

        sealed interface ServerError extends Result.Error permits ServerError.SystemError, ServerError.UnknownError {
            record SystemError(String description) implements Result.Error.ServerError {}
            record UnknownError(Response response, String description) implements Result.Error.ServerError {}
        }
        record UncaughtException(Throwable e) implements Result.Error {}
    }

}



class Main {
    public static void main(String[] args) {
/*        for (int i = 0; i < 100; i++) {
            int num = switch (aMethodWithErrors()) {
                case Success(Integer in) -> {System.out.println("Got a number " + in); yield  2;}
//                case IOError(File r) -> {System.out.println("shits fucked"); yield  1;}
            };
        }*/
    }

//    static Result<Integer> aMethodWithErrors() {
//        var rand = new Random();
//        Integer i = rand.nextInt(0, 20);
//        if (i.equals(10)) {
//            i = null;
//        }
//        return switch (i) {
//            case null -> new Success<>(10);
//            case Integer in when (in >= 0) && (in <= 10) -> new Success<>(in);
////            case Integer in when (in > 10) && (in <= 20) -> new IOError(null);
//            default -> throw new IndexOutOfBoundsException();
//        };
//    }
}