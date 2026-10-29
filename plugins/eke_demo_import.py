"""Demo plugin modeling an SNB-EKE-style "Meldung" (submission) from one reporting bank.

The EKE's actual V8 field catalog is only published as an Excel/PDF document
(not fetchable as plain text), so this is a plausible reconstruction based on
the publicly described structure (credit identification, debtor, credit
characteristics, 1:n collateral), not the literal SNB specification. It
demonstrates the generic microdata container's repeating-group mechanism
with a small, hand-written batch of individual loans, several of which carry
more than one collateral item.
"""


from sdmxdw_sdk import MicrodataClient, base_arg_parser

# One "Meldung": seven individual loans from one bank, with 0-3 collaterals
# each - deliberately varied to exercise every code path (no collateral,
# one, and several).
LOANS = [
    {
        "entityId": "EKE-LOAN-0001",
        "reportingPeriod": "2026-Q3",
        "fields": {
            "REPORTING_BANK": "UBS",
            "DEBTOR_INDUSTRY": "MANUFACTURING",
            "DEBTOR_COUNTRY": "CH",
            "LEGAL_FORM": "AG",
            "CUSTOMER_SEGMENT": "CORPORATE",
            "PRODUCT_TYPE": "INVESTMENT_LOAN",
            "CREDIT_AMOUNT": "2500000",
            "CURRENCY": "CHF",
            "INTEREST_RATE": "1.85",
            "MATURITY_DATE": "2031-09-30",
        },
        "groups": {
            "COLLATERAL": [
                {"COLLATERAL_TYPE": "MORTGAGE", "COLLATERAL_VALUE": "1800000", "COLLATERAL_CURRENCY": "CHF"},
                {"COLLATERAL_TYPE": "SECURITIES", "COLLATERAL_VALUE": "600000", "COLLATERAL_CURRENCY": "CHF"},
                {"COLLATERAL_TYPE": "GUARANTEE", "COLLATERAL_VALUE": "200000", "COLLATERAL_CURRENCY": "EUR"},
            ]
        },
    },
    {
        "entityId": "EKE-LOAN-0002",
        "reportingPeriod": "2026-Q3",
        "fields": {
            "REPORTING_BANK": "UBS",
            "DEBTOR_INDUSTRY": "SERVICES",
            "DEBTOR_COUNTRY": "CH",
            "LEGAL_FORM": "GMBH",
            "CUSTOMER_SEGMENT": "SME",
            "PRODUCT_TYPE": "OPERATING_LOAN",
            "CREDIT_AMOUNT": "150000",
            "CURRENCY": "CHF",
            "INTEREST_RATE": "3.2",
            "MATURITY_DATE": "2027-12-31",
        },
        "groups": {
            "COLLATERAL": [
                {"COLLATERAL_TYPE": "CASH_DEPOSIT", "COLLATERAL_VALUE": "50000", "COLLATERAL_CURRENCY": "CHF"},
            ]
        },
    },
    {
        "entityId": "EKE-LOAN-0003",
        "reportingPeriod": "2026-Q3",
        "fields": {
            "REPORTING_BANK": "ZKB",
            "DEBTOR_INDUSTRY": "REAL_ESTATE",
            "DEBTOR_COUNTRY": "CH",
            "LEGAL_FORM": "AG",
            "CUSTOMER_SEGMENT": "CORPORATE",
            "PRODUCT_TYPE": "MORTGAGE_LOAN",
            "CREDIT_AMOUNT": "4800000",
            "CURRENCY": "CHF",
            "INTEREST_RATE": "1.4",
            "MATURITY_DATE": "2036-06-30",
        },
        "groups": {
            "COLLATERAL": [
                {"COLLATERAL_TYPE": "MORTGAGE", "COLLATERAL_VALUE": "4800000", "COLLATERAL_CURRENCY": "CHF"},
            ]
        },
    },
    {
        "entityId": "EKE-LOAN-0004",
        "reportingPeriod": "2026-Q3",
        "fields": {
            "REPORTING_BANK": "ZKB",
            "DEBTOR_INDUSTRY": "TRADE",
            "DEBTOR_COUNTRY": "DE",
            "LEGAL_FORM": "GMBH",
            "CUSTOMER_SEGMENT": "SME",
            "PRODUCT_TYPE": "OPERATING_LOAN",
            "CREDIT_AMOUNT": "320000",
            "CURRENCY": "EUR",
            "INTEREST_RATE": "4.1",
            "MATURITY_DATE": "2028-03-31",
        },
        # Unsecured operating loan - no collateral at all.
        "groups": {"COLLATERAL": []},
    },
    {
        "entityId": "EKE-LOAN-0005",
        "reportingPeriod": "2026-Q3",
        "fields": {
            "REPORTING_BANK": "RAIFFEISEN",
            "DEBTOR_INDUSTRY": "CONSTRUCTION",
            "DEBTOR_COUNTRY": "CH",
            "LEGAL_FORM": "EINZELFIRMA",
            "CUSTOMER_SEGMENT": "SME",
            "PRODUCT_TYPE": "INVESTMENT_LOAN",
            "CREDIT_AMOUNT": "680000",
            "CURRENCY": "CHF",
            "INTEREST_RATE": "2.6",
            "MATURITY_DATE": "2030-01-31",
        },
        "groups": {
            "COLLATERAL": [
                {"COLLATERAL_TYPE": "GUARANTEE", "COLLATERAL_VALUE": "400000", "COLLATERAL_CURRENCY": "CHF"},
                {"COLLATERAL_TYPE": "CASH_DEPOSIT", "COLLATERAL_VALUE": "100000", "COLLATERAL_CURRENCY": "CHF"},
            ]
        },
    },
    {
        "entityId": "EKE-LOAN-0006",
        "reportingPeriod": "2026-Q3",
        "fields": {
            "REPORTING_BANK": "RAIFFEISEN",
            "DEBTOR_INDUSTRY": "SERVICES",
            "DEBTOR_COUNTRY": "FR",
            "LEGAL_FORM": "AG",
            "CUSTOMER_SEGMENT": "CORPORATE",
            "PRODUCT_TYPE": "OPERATING_LOAN",
            "CREDIT_AMOUNT": "95000",
            "CURRENCY": "EUR",
            "INTEREST_RATE": "3.8",
            "MATURITY_DATE": "2027-06-30",
        },
        "groups": {
            "COLLATERAL": [
                {"COLLATERAL_TYPE": "SECURITIES", "COLLATERAL_VALUE": "95000", "COLLATERAL_CURRENCY": "EUR"},
            ]
        },
    },
    {
        "entityId": "EKE-LOAN-0007",
        "reportingPeriod": "2026-Q3",
        "fields": {
            "REPORTING_BANK": "UBS",
            "DEBTOR_INDUSTRY": "MANUFACTURING",
            "DEBTOR_COUNTRY": "CH",
            "LEGAL_FORM": "AG",
            "CUSTOMER_SEGMENT": "CORPORATE",
            "PRODUCT_TYPE": "MORTGAGE_LOAN",
            "CREDIT_AMOUNT": "1200000",
            "CURRENCY": "CHF",
            "INTEREST_RATE": "1.6",
            "MATURITY_DATE": "2033-12-31",
        },
        "groups": {
            "COLLATERAL": [
                {"COLLATERAL_TYPE": "MORTGAGE", "COLLATERAL_VALUE": "900000", "COLLATERAL_CURRENCY": "CHF"},
                {"COLLATERAL_TYPE": "GUARANTEE", "COLLATERAL_VALUE": "300000", "COLLATERAL_CURRENCY": "CHF"},
            ]
        },
    },
]


def main() -> None:
    parser = base_arg_parser(__doc__)
    args = parser.parse_args()
    MicrodataClient(args.flow, dry_run=args.dry_run).send(LOANS)


if __name__ == "__main__":
    main()
