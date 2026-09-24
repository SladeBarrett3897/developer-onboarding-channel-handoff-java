public class OnboardingDecisionTest {
    public static void main(String[] args) {
        check("sms", DeveloperOnboarding.decide("email", false, true));
        check("none", DeveloperOnboarding.decide("email", false, false));
        check("sms", DeveloperOnboarding.decide("sms", true, true));
        check("email", DeveloperOnboarding.decide("sms", true, false));
        System.out.println("Onboarding decisions passed");
    }
    static void check(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
