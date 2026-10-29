"""Builds the AnaCredit data model (ECB Regulation (EU) 2016/867, Annex I) in SDMxDW.

Drives the regular Datenmanager UI endpoints (codelists, category scheme, microdata
flows, fields, categorisation), then loads a small, consistent sample reporting via
the microdata ingest API and promotes it to Beta.
"""

import os
import json
import re
import subprocess
import sys

import requests

BASE = "http://localhost:8080"
AGENCY = "ECB"
VERSION = "1.0.0"
PERIOD = "2026-09"

s = requests.Session()


def csrf(path="/microdata"):
    html = s.get(BASE + path).text
    return re.search(r'name="_csrf" value="([^"]+)"', html).group(1)


def post(path, data):
    data = dict(data)
    data["_csrf"] = csrf()
    r = s.post(BASE + path, data=data, allow_redirects=False)
    if r.status_code not in (302, 303):
        sys.exit(f"POST {path} failed: {r.status_code} {r.text[:500]}")
    return r.headers.get("Location", "")


def sql(query):
    out = subprocess.run(
        ["psql", "-h", "localhost", "-U", "sdmxdw", "-d", "sdmxdw", "-tA", "-c", query],
        env={"PGPASSWORD": os.environ["SDMXDW_DB_PASSWORD"], "PATH": "/usr/bin:/bin"},
        capture_output=True, text=True, check=True)
    return out.stdout.strip()


def login():
    token = re.search(r'name="_csrf" value="([^"]+)"', s.get(BASE + "/login").text).group(1)
    s.post(BASE + "/login", data={"username": os.environ.get("SDMXDW_ADMIN_USER", "admin@sdmxdw.local"), "password": os.environ["SDMXDW_ADMIN_PASSWORD"],
                                  "_csrf": token})


# ---------------------------------------------------------------- codelists
CODELISTS = {
    "CL_AC_COUNTRY": ("Country (ISO 3166-1)", [
        ("DE", "Germany"), ("FR", "France"), ("NL", "Netherlands"), ("IT", "Italy"),
        ("ES", "Spain"), ("AT", "Austria"), ("BE", "Belgium"), ("LU", "Luxembourg")]),
    "CL_AC_CURRENCY": ("Currency (ISO 4217)", [
        ("EUR", "Euro"), ("USD", "US dollar"), ("GBP", "Pound sterling"), ("CHF", "Swiss franc")]),
    "CL_AC_LEGAL_FORM": ("Legal form", [
        ("DE_AG", "Aktiengesellschaft"), ("DE_GMBH", "Gesellschaft mit beschränkter Haftung"),
        ("DE_KG", "Kommanditgesellschaft"), ("FR_SA", "Société anonyme"),
        ("FR_SAS", "Société par actions simplifiée"), ("NL_BV", "Besloten vennootschap"),
        ("IT_SPA", "Società per azioni"), ("ES_SL", "Sociedad limitada")]),
    "CL_AC_INSTITUTIONAL_SECTOR": ("Institutional sector (ESA 2010)", [
        ("S11", "Non-financial corporations"), ("S121", "Central bank"),
        ("S122_A", "Credit institutions"), ("S125", "Other financial intermediaries"),
        ("S128", "Insurance corporations"), ("S129", "Pension funds"),
        ("S13", "General government"), ("S14", "Households"),
        ("S15", "Non-profit institutions serving households")]),
    "CL_AC_ECONOMIC_ACTIVITY": ("Economic activity (NACE Rev. 2 sections)", [
        ("A", "Agriculture, forestry and fishing"), ("C", "Manufacturing"),
        ("F", "Construction"), ("G", "Wholesale and retail trade"),
        ("H", "Transportation and storage"), ("I", "Accommodation and food service activities"),
        ("K", "Financial and insurance activities"), ("L", "Real estate activities"),
        ("M", "Professional, scientific and technical activities")]),
    "CL_AC_ENTERPRISE_SIZE": ("Enterprise size", [
        ("LARGE", "Large enterprise"), ("MEDIUM", "Medium enterprise"),
        ("SMALL", "Small enterprise"), ("MICRO", "Micro enterprise")]),
    "CL_AC_LEGAL_PROCEEDINGS": ("Status of legal proceedings", [
        ("NO_LEGAL_ACTIONS", "No legal actions taken"),
        ("BANKRUPTCY_INSOLVENCY", "Under judicial administration, receivership or similar measures"),
        ("OTHER_LEGAL_MEASURES", "Other legal measures")]),
    "CL_AC_ACCOUNTING_STANDARD": ("Accounting standard", [
        ("IFRS", "International Financial Reporting Standards"),
        ("NGAAP", "National generally accepted accounting principles")]),
    "CL_AC_INSTRUMENT_TYPE": ("Type of instrument", [
        ("OVERDRAFT", "Overdraft"), ("CREDIT_CARD_DEBT", "Credit card debt"),
        ("REVOLVING_CREDIT", "Revolving credit other than overdrafts and credit card debt"),
        ("CREDIT_LINE", "Credit lines other than revolving credit"),
        ("REVERSE_REPO", "Reverse repurchase agreements"),
        ("TRADE_RECEIVABLES", "Trade receivables"), ("FINANCIAL_LEASE", "Financial leases"),
        ("OTHER_LOANS", "Other loans"), ("DEPOSITS", "Deposits other than reverse repurchase agreements")]),
    "CL_AC_AMORTISATION_TYPE": ("Amortisation type", [
        ("FRENCH", "French"), ("GERMAN", "German"), ("FIXED_SCHEDULE", "Fixed amortisation schedule"),
        ("BULLET", "Bullet"), ("OTHER", "Other")]),
    "CL_AC_INTEREST_RATE_TYPE": ("Interest rate type", [
        ("FIXED", "Fixed"), ("VARIABLE", "Variable"), ("MIXED", "Mixed")]),
    "CL_AC_FREQUENCY": ("Payment / interest rate reset frequency", [
        ("MONTHLY", "Monthly"), ("QUARTERLY", "Quarterly"), ("SEMI_ANNUAL", "Semi-annual"),
        ("ANNUAL", "Annual"), ("BULLET", "Bullet"), ("ZERO_COUPON", "Zero coupon"),
        ("OTHER", "Other"), ("NOT_APPLICABLE", "Not applicable")]),
    "CL_AC_PURPOSE": ("Purpose", [
        ("RESIDENTIAL_RE", "Residential real estate purchase"),
        ("COMMERCIAL_RE", "Commercial real estate purchase"), ("MARGIN_LENDING", "Margin lending"),
        ("DEBT_FINANCING", "Debt financing"), ("IMPORTS", "Imports"), ("EXPORTS", "Exports"),
        ("CONSTRUCTION_INVESTMENT", "Construction investment"),
        ("WORKING_CAPITAL", "Working capital facility"), ("OTHER", "Other purposes")]),
    "CL_AC_RECOURSE": ("Recourse", [("RECOURSE", "Recourse"), ("NO_RECOURSE", "No recourse")]),
    "CL_AC_YES_NO_PROJECT_FINANCE": ("Project finance loan", [
        ("PROJECT_FINANCE", "Project finance loan"), ("NOT_PROJECT_FINANCE", "Non project finance loan")]),
    "CL_AC_SUBORDINATED_DEBT": ("Subordinated debt", [
        ("SUBORDINATED", "Subordinated debt"), ("NOT_SUBORDINATED", "Non-subordinated debt")]),
    "CL_AC_FIDUCIARY": ("Fiduciary instrument", [
        ("FIDUCIARY", "Fiduciary instrument"), ("NOT_FIDUCIARY", "Non-fiduciary instrument")]),
    "CL_AC_DEFAULT_STATUS": ("Default status", [
        ("NOT_IN_DEFAULT", "Not in default"),
        ("DEFAULT_UNLIKELY_TO_PAY", "Default because unlikely to pay"),
        ("DEFAULT_PAST_DUE", "Default because more than 90/180 days past due"),
        ("DEFAULT_BOTH", "Default because both unlikely to pay and more than 90/180 days past due")]),
    "CL_AC_SECURITISATION_TYPE": ("Type of securitisation", [
        ("NOT_SECURITISED", "Not securitised"), ("TRADITIONAL", "Traditional securitisation"),
        ("SYNTHETIC", "Synthetic securitisation")]),
    "CL_AC_COUNTERPARTY_ROLE": ("Counterparty role", [
        ("CREDITOR", "Creditor"), ("DEBTOR", "Debtor"), ("SERVICER", "Servicer"),
        ("ORIGINATOR", "Originator")]),
    "CL_AC_ACCOUNTING_CLASSIFICATION": ("Accounting classification of instruments", [
        ("AMORTISED_COST", "Financial assets at amortised cost"),
        ("FV_OCI", "Financial assets at fair value through other comprehensive income"),
        ("FV_PL_MANDATORY", "Non-trading financial assets mandatorily at fair value through profit or loss"),
        ("FV_PL_DESIGNATED", "Financial assets designated at fair value through profit or loss"),
        ("HELD_FOR_TRADING", "Financial assets held for trading")]),
    "CL_AC_BALANCE_SHEET_RECOGNITION": ("Balance sheet recognition", [
        ("ENTIRELY_RECOGNISED", "Entirely recognised"),
        ("CONTINUING_INVOLVEMENT", "Recognised to the extent of the institution's continuing involvement"),
        ("ENTIRELY_DERECOGNISED", "Entirely derecognised")]),
    "CL_AC_ENCUMBRANCE_SOURCE": ("Sources of encumbrance", [
        ("NO_ENCUMBRANCE", "No encumbrance"), ("CENTRAL_BANK_FUNDING", "Central bank funding"),
        ("REPURCHASE_AGREEMENTS", "Repurchase agreements"), ("DERIVATIVES", "Derivatives"),
        ("COVERED_BONDS", "Covered bonds issued"), ("ABS", "Asset-backed securities issued"),
        ("OTHER", "Other sources of encumbrance")]),
    "CL_AC_IMPAIRMENT_TYPE": ("Type of impairment", [
        ("STAGE_1", "Stage 1 (IFRS)"), ("STAGE_2", "Stage 2 (IFRS)"), ("STAGE_3", "Stage 3 (IFRS)"),
        ("GENERAL_ALLOWANCES", "General allowances (GAAP)"),
        ("SPECIFIC_ALLOWANCES", "Specific allowances (GAAP)")]),
    "CL_AC_IMPAIRMENT_METHOD": ("Impairment assessment method", [
        ("INDIVIDUALLY", "Individually assessed"), ("COLLECTIVELY", "Collectively assessed"),
        ("NOT_SUBJECT", "Not subject to impairment")]),
    "CL_AC_PERFORMING_STATUS": ("Performing status of the instrument", [
        ("PERFORMING", "Performing"), ("NON_PERFORMING", "Non-performing")]),
    "CL_AC_FORBEARANCE_STATUS": ("Status of forbearance and renegotiation", [
        ("NOT_FORBORNE", "Not forborne or renegotiated"),
        ("FORBORNE_BELOW_MARKET", "Forborne: instruments with modified interest rate below market conditions"),
        ("FORBORNE_REFINANCING", "Forborne: total or partial refinancing of troubled debt contract"),
        ("FORBORNE_OTHER", "Forborne: instruments with other modified terms and conditions"),
        ("RENEGOTIATED", "Renegotiated instrument without forbearance measures")]),
    "CL_AC_PRUDENTIAL_PORTFOLIO": ("Prudential portfolio", [
        ("TRADING_BOOK", "Trading book"), ("NON_TRADING_BOOK", "Non-trading book")]),
    "CL_AC_PROTECTION_TYPE": ("Type of protection", [
        ("GOLD", "Gold"), ("CURRENCY_DEPOSITS", "Currency and deposits"), ("SECURITIES", "Securities"),
        ("LOANS", "Loans"), ("EQUITY_FUND_SHARES", "Equity and investment fund shares or units"),
        ("CREDIT_DERIVATIVES", "Credit derivatives"),
        ("FINANCIAL_GUARANTEES", "Financial guarantees other than credit derivatives"),
        ("TRADE_RECEIVABLES", "Trade receivables"), ("LIFE_INSURANCE", "Life insurance policies pledged"),
        ("OTHER_FINANCIAL", "Other financial assets"),
        ("RESIDENTIAL_RE", "Residential real estate collateral"),
        ("OFFICES_COMMERCIAL_PREMISES", "Offices and commercial premises"),
        ("COMMERCIAL_RE", "Commercial real estate collateral"),
        ("OTHER_PHYSICAL", "Other physical collateral")]),
    "CL_AC_PROTECTION_VALUE_TYPE": ("Type of protection value", [
        ("NOTIONAL_AMOUNT", "Notional amount"), ("FAIR_VALUE", "Fair value"),
        ("MARKET_VALUE", "Market value"), ("LONG_TERM_SUSTAINABLE", "Long-term sustainable value"),
        ("OTHER", "Other protection value")]),
    "CL_AC_PROTECTION_VALUATION": ("Protection valuation approach", [
        ("MARK_TO_MARKET", "Mark-to-market"), ("COUNTERPARTY_ESTIMATION", "Counterparty estimation"),
        ("CREDITOR_VALUATION", "Creditor valuation"), ("THIRD_PARTY_VALUATION", "Third party valuation"),
        ("OTHER", "Other type of valuation")]),
}

# ------------------------------------------------------------------ datasets
# (field code, label, type, codelist|None, required, facet|None)  - "REF:<flow key>" = reference
S, N, D = "STRING", "NUMBER", "DATE"
FLOWS = [
    ("COUNTERPARTY_REFERENCE", "MDF_AC_COUNTERPARTY_REFERENCE", "Counterparty reference data",
     "Dataset 1: static data of debtors, creditors, protection providers and their group structure", [
        ("LEI", "Legal Entity Identifier (LEI)", S, None, False, None),
        ("NATIONAL_IDENTIFIER", "National identifier", S, None, False, None),
        ("HEAD_OFFICE", "Head office undertaking identifier", "REF:COUNTERPARTY_REFERENCE", None, False, None),
        ("IMMEDIATE_PARENT", "Immediate parent undertaking identifier", "REF:COUNTERPARTY_REFERENCE", None, False, None),
        ("ULTIMATE_PARENT", "Ultimate parent undertaking identifier", "REF:COUNTERPARTY_REFERENCE", None, False, None),
        ("NAME", "Name", S, None, True, None),
        ("STREET", "Address: street", S, None, False, None),
        ("CITY", "Address: city/town/village", S, None, False, None),
        ("POSTAL_CODE", "Address: postal code", S, None, False, None),
        ("COUNTRY", "Address: country", S, "CL_AC_COUNTRY", True, "facet1"),
        ("LEGAL_FORM", "Legal form", S, "CL_AC_LEGAL_FORM", False, None),
        ("INSTITUTIONAL_SECTOR", "Institutional sector", S, "CL_AC_INSTITUTIONAL_SECTOR", True, "facet2"),
        ("ECONOMIC_ACTIVITY", "Economic activity", S, "CL_AC_ECONOMIC_ACTIVITY", False, "facet3"),
        ("LEGAL_PROCEEDINGS_STATUS", "Status of legal proceedings", S, "CL_AC_LEGAL_PROCEEDINGS", False, None),
        ("LEGAL_PROCEEDINGS_DATE", "Date of initiation of legal proceedings", D, None, False, None),
        ("ENTERPRISE_SIZE", "Enterprise size", S, "CL_AC_ENTERPRISE_SIZE", False, "facet4"),
        ("ENTERPRISE_SIZE_DATE", "Date of enterprise size", D, None, False, None),
        ("NUMBER_OF_EMPLOYEES", "Number of employees", N, None, False, None),
        ("BALANCE_SHEET_TOTAL", "Balance sheet total", N, None, False, None),
        ("ANNUAL_TURNOVER", "Annual turnover", N, None, False, None),
        ("ACCOUNTING_STANDARD", "Accounting standard", S, "CL_AC_ACCOUNTING_STANDARD", False, None)]),
    ("INSTRUMENT", "MDF_AC_INSTRUMENT", "Instrument data",
     "Dataset 2: contractual terms of the credit instrument (entity id = observed agent / contract / instrument)", [
        ("OBSERVED_AGENT", "Observed agent identifier", "REF:COUNTERPARTY_REFERENCE", None, True, "facet1"),
        ("CONTRACT_ID", "Contract identifier", S, None, True, None),
        ("INSTRUMENT_ID", "Instrument identifier", S, None, True, None),
        ("TYPE_OF_INSTRUMENT", "Type of instrument", S, "CL_AC_INSTRUMENT_TYPE", True, "facet2"),
        ("AMORTISATION_TYPE", "Amortisation type", S, "CL_AC_AMORTISATION_TYPE", False, None),
        ("CURRENCY", "Currency", S, "CL_AC_CURRENCY", True, "facet3"),
        ("FIDUCIARY_INSTRUMENT", "Fiduciary instrument", S, "CL_AC_FIDUCIARY", False, None),
        ("INCEPTION_DATE", "Inception date", D, None, True, None),
        ("END_DATE_INTEREST_ONLY", "End date of interest-only period", D, None, False, None),
        ("INTEREST_RATE_CAP", "Interest rate cap", N, None, False, None),
        ("INTEREST_RATE_FLOOR", "Interest rate floor", N, None, False, None),
        ("INTEREST_RATE_RESET_FREQUENCY", "Interest rate reset frequency", S, "CL_AC_FREQUENCY", False, None),
        ("INTEREST_RATE_SPREAD", "Interest rate spread/margin", N, None, False, None),
        ("INTEREST_RATE_TYPE", "Interest rate type", S, "CL_AC_INTEREST_RATE_TYPE", True, "facet4"),
        ("LEGAL_FINAL_MATURITY_DATE", "Legal final maturity date", D, None, False, None),
        ("COMMITMENT_AMOUNT_AT_INCEPTION", "Commitment amount at inception", N, None, False, None),
        ("PAYMENT_FREQUENCY", "Payment frequency", S, "CL_AC_FREQUENCY", False, None),
        ("PROJECT_FINANCE_LOAN", "Project finance loan", S, "CL_AC_YES_NO_PROJECT_FINANCE", False, None),
        ("PURPOSE", "Purpose", S, "CL_AC_PURPOSE", False, None),
        ("RECOURSE", "Recourse", S, "CL_AC_RECOURSE", False, None),
        ("REFERENCE_RATE", "Reference rate", S, None, False, None),
        ("SETTLEMENT_DATE", "Settlement date", D, None, False, None),
        ("SUBORDINATED_DEBT", "Subordinated debt", S, "CL_AC_SUBORDINATED_DEBT", False, None),
        ("SYNDICATED_CONTRACT_ID", "Syndicated contract identifier", S, None, False, None),
        ("FV_CHANGES_BEFORE_PURCHASE", "Fair value changes due to changes in credit risk before purchase", N, None, False, None)]),
    ("FINANCIAL", "MDF_AC_FINANCIAL", "Financial data",
     "Dataset 3: monthly financial state of the instrument", [
        ("INSTRUMENT", "Instrument", "REF:INSTRUMENT", None, True, "facet1"),
        ("INTEREST_RATE", "Interest rate", N, None, True, None),
        ("NEXT_INTEREST_RATE_RESET_DATE", "Next interest rate reset date", D, None, False, None),
        ("DEFAULT_STATUS", "Default status of the instrument", S, "CL_AC_DEFAULT_STATUS", True, "facet2"),
        ("DEFAULT_STATUS_DATE", "Date of the default status of the instrument", D, None, False, None),
        ("TRANSFERRED_AMOUNT", "Transferred amount", N, None, False, None),
        ("ARREARS", "Arrears for the instrument", N, None, False, None),
        ("PAST_DUE_DATE", "Date of past due for the instrument", D, None, False, None),
        ("TYPE_OF_SECURITISATION", "Type of securitisation", S, "CL_AC_SECURITISATION_TYPE", False, "facet3"),
        ("OUTSTANDING_NOMINAL_AMOUNT", "Outstanding nominal amount", N, None, True, None),
        ("ACCRUED_INTEREST", "Accrued interest", N, None, False, None),
        ("OFF_BALANCE_SHEET_AMOUNT", "Off-balance-sheet amount", N, None, False, None)]),
    ("COUNTERPARTY_INSTRUMENT", "MDF_AC_COUNTERPARTY_INSTRUMENT", "Counterparty-instrument data",
     "Dataset 4: which counterparty acts in which role on an instrument", [
        ("INSTRUMENT", "Instrument", "REF:INSTRUMENT", None, True, "facet1"),
        ("COUNTERPARTY", "Counterparty identifier", "REF:COUNTERPARTY_REFERENCE", None, True, "facet2"),
        ("COUNTERPARTY_ROLE", "Counterparty role", S, "CL_AC_COUNTERPARTY_ROLE", True, "facet3")]),
    ("JOINT_LIABILITIES", "MDF_AC_JOINT_LIABILITIES", "Joint liabilities data",
     "Dataset 5: debtors jointly liable for an instrument", [
        ("INSTRUMENT", "Instrument", "REF:INSTRUMENT", None, True, "facet1"),
        ("COUNTERPARTY", "Counterparty identifier", "REF:COUNTERPARTY_REFERENCE", None, True, "facet2"),
        ("JOINT_LIABILITY_AMOUNT", "Joint liability amount", N, None, True, None)]),
    ("ACCOUNTING", "MDF_AC_ACCOUNTING", "Accounting data",
     "Dataset 6: quarterly accounting view of the instrument", [
        ("INSTRUMENT", "Instrument", "REF:INSTRUMENT", None, True, "facet1"),
        ("ACCOUNTING_CLASSIFICATION", "Accounting classification of instruments", S, "CL_AC_ACCOUNTING_CLASSIFICATION", True, "facet2"),
        ("BALANCE_SHEET_RECOGNITION", "Balance sheet recognition", S, "CL_AC_BALANCE_SHEET_RECOGNITION", True, None),
        ("SOURCES_OF_ENCUMBRANCE", "Sources of encumbrance", S, "CL_AC_ENCUMBRANCE_SOURCE", False, None),
        ("ACCUMULATED_WRITE_OFFS", "Accumulated write-offs", N, None, False, None),
        ("ACCUMULATED_IMPAIRMENT", "Accumulated impairment amount", N, None, False, None),
        ("TYPE_OF_IMPAIRMENT", "Type of impairment", S, "CL_AC_IMPAIRMENT_TYPE", False, "facet3"),
        ("IMPAIRMENT_ASSESSMENT_METHOD", "Impairment assessment method", S, "CL_AC_IMPAIRMENT_METHOD", False, None),
        ("ACCUMULATED_FV_CHANGES_CREDIT_RISK", "Accumulated changes in fair value due to credit risk", N, None, False, None),
        ("PERFORMING_STATUS", "Performing status of the instrument", S, "CL_AC_PERFORMING_STATUS", True, "facet4"),
        ("PERFORMING_STATUS_DATE", "Date of the performing status of the instrument", D, None, False, None),
        ("PROVISIONS_OFF_BALANCE_SHEET", "Provisions associated with off-balance-sheet exposures", N, None, False, None),
        ("FORBEARANCE_STATUS", "Status of forbearance and renegotiation", S, "CL_AC_FORBEARANCE_STATUS", False, None),
        ("FORBEARANCE_STATUS_DATE", "Date of the forbearance and renegotiation status", D, None, False, None),
        ("CUMULATIVE_RECOVERIES", "Cumulative recoveries since default", N, None, False, None),
        ("PRUDENTIAL_PORTFOLIO", "Prudential portfolio", S, "CL_AC_PRUDENTIAL_PORTFOLIO", False, None),
        ("CARRYING_AMOUNT", "Carrying amount", N, None, False, None)]),
    ("PROTECTION_RECEIVED", "MDF_AC_PROTECTION_RECEIVED", "Protection received data",
     "Dataset 7: collateral and guarantees received (entity id = protection identifier)", [
        ("PROTECTION_PROVIDER", "Protection provider identifier", "REF:COUNTERPARTY_REFERENCE", None, True, "facet1"),
        ("TYPE_OF_PROTECTION", "Type of protection", S, "CL_AC_PROTECTION_TYPE", True, "facet2"),
        ("PROTECTION_VALUE", "Protection value", N, None, True, None),
        ("TYPE_OF_PROTECTION_VALUE", "Type of protection value", S, "CL_AC_PROTECTION_VALUE_TYPE", True, None),
        ("PROTECTION_VALUATION_APPROACH", "Protection valuation approach", S, "CL_AC_PROTECTION_VALUATION", True, "facet3"),
        ("REAL_ESTATE_LOCATION", "Real estate collateral location (NUTS 3)", S, None, False, None),
        ("PROTECTION_VALUE_DATE", "Date of protection value", D, None, False, None),
        ("PROTECTION_MATURITY_DATE", "Maturity date of the protection", D, None, False, None),
        ("ORIGINAL_PROTECTION_VALUE", "Original protection value", N, None, False, None),
        ("ORIGINAL_PROTECTION_VALUE_DATE", "Date of original protection value", D, None, False, None)]),
    ("INSTRUMENT_PROTECTION", "MDF_AC_INSTRUMENT_PROTECTION", "Instrument-protection received data",
     "Dataset 8: allocation of protection received to instruments (n:m)", [
        ("INSTRUMENT", "Instrument", "REF:INSTRUMENT", None, True, "facet1"),
        ("PROTECTION", "Protection identifier", "REF:PROTECTION_RECEIVED", None, True, "facet2"),
        ("PROTECTION_ALLOCATED_VALUE", "Protection allocated value", N, None, True, None),
        ("THIRD_PARTY_PRIORITY_CLAIMS", "Third party priority claims against the protection", N, None, False, None)]),
    ("COUNTERPARTY_RISK", "MDF_AC_COUNTERPARTY_RISK", "Counterparty risk data",
     "Dataset 9: probability of default of debtors (IRB institutions)", [
        ("COUNTERPARTY", "Counterparty identifier", "REF:COUNTERPARTY_REFERENCE", None, True, "facet1"),
        ("PROBABILITY_OF_DEFAULT", "Probability of default", N, None, True, None)]),
    ("COUNTERPARTY_DEFAULT", "MDF_AC_COUNTERPARTY_DEFAULT", "Counterparty default data",
     "Dataset 10: default status of the counterparty", [
        ("COUNTERPARTY", "Counterparty identifier", "REF:COUNTERPARTY_REFERENCE", None, True, "facet1"),
        ("DEFAULT_STATUS", "Default status of the counterparty", S, "CL_AC_DEFAULT_STATUS", True, "facet2"),
        ("DEFAULT_STATUS_DATE", "Date of the default status of the counterparty", D, None, False, None)]),
]

# --------------------------------------------------------------- sample data
BANK = "DE00001"
CPS = [  # parents before children (self references are validated on ingest)
    ("DE00001", dict(LEI="529900MUSTERBANK0001", NAME="Musterbank AG", STREET="Taunusanlage 1", CITY="Frankfurt am Main", POSTAL_CODE="60329", COUNTRY="DE", LEGAL_FORM="DE_AG", INSTITUTIONAL_SECTOR="S122_A", ECONOMIC_ACTIVITY="K", ENTERPRISE_SIZE="LARGE", ACCOUNTING_STANDARD="IFRS")),
    ("DE10000", dict(LEI="529900ALPHAHOLDING01", NAME="Alpha Holding AG", CITY="Stuttgart", POSTAL_CODE="70173", COUNTRY="DE", LEGAL_FORM="DE_AG", INSTITUTIONAL_SECTOR="S11", ECONOMIC_ACTIVITY="M", ENTERPRISE_SIZE="LARGE", NUMBER_OF_EMPLOYEES="2100", BALANCE_SHEET_TOTAL="640000000", ANNUAL_TURNOVER="910000000", ACCOUNTING_STANDARD="IFRS")),
    ("DE10001", dict(NATIONAL_IDENTIFIER="HRB 712345", IMMEDIATE_PARENT="DE10000", ULTIMATE_PARENT="DE10000", NAME="Alpha Maschinenbau GmbH", STREET="Industriestr. 12", CITY="Esslingen", POSTAL_CODE="73728", COUNTRY="DE", LEGAL_FORM="DE_GMBH", INSTITUTIONAL_SECTOR="S11", ECONOMIC_ACTIVITY="C", LEGAL_PROCEEDINGS_STATUS="NO_LEGAL_ACTIONS", ENTERPRISE_SIZE="MEDIUM", ENTERPRISE_SIZE_DATE="2025-12-31", NUMBER_OF_EMPLOYEES="180", BALANCE_SHEET_TOTAL="42000000", ANNUAL_TURNOVER="38500000", ACCOUNTING_STANDARD="NGAAP")),
    ("DE10003", dict(NATIONAL_IDENTIFIER="HRB 55120", NAME="Beta Beteiligungs GmbH", CITY="Hamburg", POSTAL_CODE="20095", COUNTRY="DE", LEGAL_FORM="DE_GMBH", INSTITUTIONAL_SECTOR="S11", ECONOMIC_ACTIVITY="M", ENTERPRISE_SIZE="SMALL", ACCOUNTING_STANDARD="NGAAP")),
    ("DE10002", dict(NATIONAL_IDENTIFIER="HRA 88231", IMMEDIATE_PARENT="DE10003", ULTIMATE_PARENT="DE10003", NAME="Beta Logistik KG", CITY="Hamburg", POSTAL_CODE="20457", COUNTRY="DE", LEGAL_FORM="DE_KG", INSTITUTIONAL_SECTOR="S11", ECONOMIC_ACTIVITY="H", LEGAL_PROCEEDINGS_STATUS="NO_LEGAL_ACTIONS", ENTERPRISE_SIZE="SMALL", NUMBER_OF_EMPLOYEES="42", ANNUAL_TURNOVER="9100000", ACCOUNTING_STANDARD="NGAAP")),
    ("DE30001", dict(NAME="Bürgschaftsbank Hessen GmbH", CITY="Wiesbaden", POSTAL_CODE="65185", COUNTRY="DE", LEGAL_FORM="DE_GMBH", INSTITUTIONAL_SECTOR="S125", ECONOMIC_ACTIVITY="K", ENTERPRISE_SIZE="SMALL", ACCOUNTING_STANDARD="NGAAP")),
    ("FR20001", dict(NATIONAL_IDENTIFIER="SIREN 812345678", NAME="Bistro Lumière SAS", STREET="12 rue de la Paix", CITY="Lyon", POSTAL_CODE="69002", COUNTRY="FR", LEGAL_FORM="FR_SAS", INSTITUTIONAL_SECTOR="S11", ECONOMIC_ACTIVITY="I", LEGAL_PROCEEDINGS_STATUS="BANKRUPTCY_INSOLVENCY", LEGAL_PROCEEDINGS_DATE="2026-07-15", ENTERPRISE_SIZE="MICRO", NUMBER_OF_EMPLOYEES="7", ANNUAL_TURNOVER="610000", ACCOUNTING_STANDARD="NGAAP")),
    ("NL40001", dict(LEI="724500GAMMARETAIL001", NAME="Gamma Retail B.V.", CITY="Utrecht", POSTAL_CODE="3511", COUNTRY="NL", LEGAL_FORM="NL_BV", INSTITUTIONAL_SECTOR="S11", ECONOMIC_ACTIVITY="G", ENTERPRISE_SIZE="LARGE", NUMBER_OF_EMPLOYEES="3400", BALANCE_SHEET_TOTAL="1250000000", ANNUAL_TURNOVER="2300000000", ACCOUNTING_STANDARD="IFRS")),
]
INSTRUMENTS = [
    ("C001", "I001", dict(TYPE_OF_INSTRUMENT="CREDIT_LINE", AMORTISATION_TYPE="OTHER", CURRENCY="EUR", FIDUCIARY_INSTRUMENT="NOT_FIDUCIARY", INCEPTION_DATE="2024-03-01", INTEREST_RATE_RESET_FREQUENCY="QUARTERLY", INTEREST_RATE_SPREAD="0.0185", INTEREST_RATE_TYPE="VARIABLE", LEGAL_FINAL_MATURITY_DATE="2029-02-28", COMMITMENT_AMOUNT_AT_INCEPTION="2000000", PAYMENT_FREQUENCY="QUARTERLY", PROJECT_FINANCE_LOAN="NOT_PROJECT_FINANCE", PURPOSE="WORKING_CAPITAL", RECOURSE="RECOURSE", REFERENCE_RATE="EURIBOR_3M", SETTLEMENT_DATE="2024-03-01", SUBORDINATED_DEBT="NOT_SUBORDINATED")),
    ("C002", "I002", dict(TYPE_OF_INSTRUMENT="OTHER_LOANS", AMORTISATION_TYPE="FRENCH", CURRENCY="EUR", FIDUCIARY_INSTRUMENT="NOT_FIDUCIARY", INCEPTION_DATE="2023-06-15", INTEREST_RATE_RESET_FREQUENCY="NOT_APPLICABLE", INTEREST_RATE_TYPE="FIXED", LEGAL_FINAL_MATURITY_DATE="2033-06-15", COMMITMENT_AMOUNT_AT_INCEPTION="800000", PAYMENT_FREQUENCY="MONTHLY", PROJECT_FINANCE_LOAN="NOT_PROJECT_FINANCE", PURPOSE="CONSTRUCTION_INVESTMENT", RECOURSE="RECOURSE", SETTLEMENT_DATE="2023-07-01", SUBORDINATED_DEBT="NOT_SUBORDINATED")),
    ("C003", "I003", dict(TYPE_OF_INSTRUMENT="OVERDRAFT", AMORTISATION_TYPE="OTHER", CURRENCY="EUR", FIDUCIARY_INSTRUMENT="NOT_FIDUCIARY", INCEPTION_DATE="2022-01-10", INTEREST_RATE_RESET_FREQUENCY="MONTHLY", INTEREST_RATE_SPREAD="0.065", INTEREST_RATE_TYPE="VARIABLE", COMMITMENT_AMOUNT_AT_INCEPTION="50000", PAYMENT_FREQUENCY="OTHER", PROJECT_FINANCE_LOAN="NOT_PROJECT_FINANCE", PURPOSE="WORKING_CAPITAL", RECOURSE="RECOURSE", REFERENCE_RATE="EURIBOR_1M", SUBORDINATED_DEBT="NOT_SUBORDINATED")),
    ("C004", "I004", dict(TYPE_OF_INSTRUMENT="FINANCIAL_LEASE", AMORTISATION_TYPE="FIXED_SCHEDULE", CURRENCY="EUR", FIDUCIARY_INSTRUMENT="NOT_FIDUCIARY", INCEPTION_DATE="2025-02-01", INTEREST_RATE_RESET_FREQUENCY="NOT_APPLICABLE", INTEREST_RATE_TYPE="FIXED", LEGAL_FINAL_MATURITY_DATE="2030-01-31", COMMITMENT_AMOUNT_AT_INCEPTION="350000", PAYMENT_FREQUENCY="MONTHLY", PROJECT_FINANCE_LOAN="NOT_PROJECT_FINANCE", PURPOSE="OTHER", RECOURSE="RECOURSE", SETTLEMENT_DATE="2025-02-01", SUBORDINATED_DEBT="NOT_SUBORDINATED")),
    ("C005", "I005", dict(TYPE_OF_INSTRUMENT="REVOLVING_CREDIT", AMORTISATION_TYPE="BULLET", CURRENCY="EUR", FIDUCIARY_INSTRUMENT="NOT_FIDUCIARY", INCEPTION_DATE="2025-09-30", INTEREST_RATE_CAP="0.06", INTEREST_RATE_FLOOR="0.0", INTEREST_RATE_RESET_FREQUENCY="MONTHLY", INTEREST_RATE_SPREAD="0.0095", INTEREST_RATE_TYPE="VARIABLE", LEGAL_FINAL_MATURITY_DATE="2030-09-30", COMMITMENT_AMOUNT_AT_INCEPTION="5000000", PAYMENT_FREQUENCY="MONTHLY", PROJECT_FINANCE_LOAN="NOT_PROJECT_FINANCE", PURPOSE="WORKING_CAPITAL", RECOURSE="RECOURSE", REFERENCE_RATE="EURIBOR_1M", SETTLEMENT_DATE="2025-10-01", SUBORDINATED_DEBT="NOT_SUBORDINATED", SYNDICATED_CONTRACT_ID="SYND-2025-GAMMA")),
]
DEBTORS = {"I001": ["DE10001"], "I002": ["DE10001"], "I003": ["FR20001"], "I004": ["DE10002", "DE10003"], "I005": ["NL40001"]}
FINANCIAL = {
    "I001": dict(INTEREST_RATE="0.0391", NEXT_INTEREST_RATE_RESET_DATE="2026-12-01", DEFAULT_STATUS="NOT_IN_DEFAULT", TYPE_OF_SECURITISATION="NOT_SECURITISED", OUTSTANDING_NOMINAL_AMOUNT="1250000", ACCRUED_INTEREST="4073", OFF_BALANCE_SHEET_AMOUNT="750000"),
    "I002": dict(INTEREST_RATE="0.0345", DEFAULT_STATUS="NOT_IN_DEFAULT", TYPE_OF_SECURITISATION="NOT_SECURITISED", OUTSTANDING_NOMINAL_AMOUNT="612400", ACCRUED_INTEREST="1760", OFF_BALANCE_SHEET_AMOUNT="0"),
    "I003": dict(INTEREST_RATE="0.0855", DEFAULT_STATUS="DEFAULT_PAST_DUE", DEFAULT_STATUS_DATE="2026-08-20", ARREARS="9450", PAST_DUE_DATE="2026-05-20", TYPE_OF_SECURITISATION="NOT_SECURITISED", OUTSTANDING_NOMINAL_AMOUNT="38200", ACCRUED_INTEREST="0", OFF_BALANCE_SHEET_AMOUNT="0"),
    "I004": dict(INTEREST_RATE="0.0412", DEFAULT_STATUS="NOT_IN_DEFAULT", TYPE_OF_SECURITISATION="NOT_SECURITISED", OUTSTANDING_NOMINAL_AMOUNT="291700", ACCRUED_INTEREST="1001", OFF_BALANCE_SHEET_AMOUNT="0"),
    "I005": dict(INTEREST_RATE="0.0301", NEXT_INTEREST_RATE_RESET_DATE="2026-10-31", DEFAULT_STATUS="NOT_IN_DEFAULT", TYPE_OF_SECURITISATION="NOT_SECURITISED", OUTSTANDING_NOMINAL_AMOUNT="3200000", ACCRUED_INTEREST="8026", OFF_BALANCE_SHEET_AMOUNT="1800000"),
}
ACCOUNTING = {
    "I001": dict(ACCOUNTING_CLASSIFICATION="AMORTISED_COST", BALANCE_SHEET_RECOGNITION="ENTIRELY_RECOGNISED", SOURCES_OF_ENCUMBRANCE="NO_ENCUMBRANCE", ACCUMULATED_IMPAIRMENT="6250", TYPE_OF_IMPAIRMENT="STAGE_1", IMPAIRMENT_ASSESSMENT_METHOD="COLLECTIVELY", PERFORMING_STATUS="PERFORMING", PERFORMING_STATUS_DATE="2024-03-01", PROVISIONS_OFF_BALANCE_SHEET="1100", FORBEARANCE_STATUS="NOT_FORBORNE", PRUDENTIAL_PORTFOLIO="NON_TRADING_BOOK", CARRYING_AMOUNT="1247823"),
    "I002": dict(ACCOUNTING_CLASSIFICATION="AMORTISED_COST", BALANCE_SHEET_RECOGNITION="ENTIRELY_RECOGNISED", SOURCES_OF_ENCUMBRANCE="COVERED_BONDS", ACCUMULATED_IMPAIRMENT="3100", TYPE_OF_IMPAIRMENT="STAGE_1", IMPAIRMENT_ASSESSMENT_METHOD="COLLECTIVELY", PERFORMING_STATUS="PERFORMING", PERFORMING_STATUS_DATE="2023-06-15", FORBEARANCE_STATUS="NOT_FORBORNE", PRUDENTIAL_PORTFOLIO="NON_TRADING_BOOK", CARRYING_AMOUNT="611060"),
    "I003": dict(ACCOUNTING_CLASSIFICATION="AMORTISED_COST", BALANCE_SHEET_RECOGNITION="ENTIRELY_RECOGNISED", SOURCES_OF_ENCUMBRANCE="NO_ENCUMBRANCE", ACCUMULATED_WRITE_OFFS="2000", ACCUMULATED_IMPAIRMENT="21500", TYPE_OF_IMPAIRMENT="STAGE_3", IMPAIRMENT_ASSESSMENT_METHOD="INDIVIDUALLY", PERFORMING_STATUS="NON_PERFORMING", PERFORMING_STATUS_DATE="2026-08-20", FORBEARANCE_STATUS="FORBORNE_OTHER", FORBEARANCE_STATUS_DATE="2026-04-01", CUMULATIVE_RECOVERIES="1500", PRUDENTIAL_PORTFOLIO="NON_TRADING_BOOK", CARRYING_AMOUNT="16700"),
    "I004": dict(ACCOUNTING_CLASSIFICATION="AMORTISED_COST", BALANCE_SHEET_RECOGNITION="ENTIRELY_RECOGNISED", SOURCES_OF_ENCUMBRANCE="NO_ENCUMBRANCE", ACCUMULATED_IMPAIRMENT="4300", TYPE_OF_IMPAIRMENT="STAGE_2", IMPAIRMENT_ASSESSMENT_METHOD="COLLECTIVELY", PERFORMING_STATUS="PERFORMING", PERFORMING_STATUS_DATE="2025-02-01", FORBEARANCE_STATUS="NOT_FORBORNE", PRUDENTIAL_PORTFOLIO="NON_TRADING_BOOK", CARRYING_AMOUNT="288401"),
    "I005": dict(ACCOUNTING_CLASSIFICATION="AMORTISED_COST", BALANCE_SHEET_RECOGNITION="ENTIRELY_RECOGNISED", SOURCES_OF_ENCUMBRANCE="CENTRAL_BANK_FUNDING", ACCUMULATED_IMPAIRMENT="9600", TYPE_OF_IMPAIRMENT="STAGE_1", IMPAIRMENT_ASSESSMENT_METHOD="COLLECTIVELY", PERFORMING_STATUS="PERFORMING", PERFORMING_STATUS_DATE="2025-09-30", PROVISIONS_OFF_BALANCE_SHEET="2700", FORBEARANCE_STATUS="NOT_FORBORNE", PRUDENTIAL_PORTFOLIO="NON_TRADING_BOOK", CARRYING_AMOUNT="3198426"),
}
PROTECTIONS = {
    "P001": dict(PROTECTION_PROVIDER="DE10001", TYPE_OF_PROTECTION="COMMERCIAL_RE", PROTECTION_VALUE="1500000", TYPE_OF_PROTECTION_VALUE="LONG_TERM_SUSTAINABLE", PROTECTION_VALUATION_APPROACH="THIRD_PARTY_VALUATION", REAL_ESTATE_LOCATION="DE113", PROTECTION_VALUE_DATE="2026-01-15", ORIGINAL_PROTECTION_VALUE="1400000", ORIGINAL_PROTECTION_VALUE_DATE="2023-05-30"),
    "P002": dict(PROTECTION_PROVIDER="DE30001", TYPE_OF_PROTECTION="FINANCIAL_GUARANTEES", PROTECTION_VALUE="400000", TYPE_OF_PROTECTION_VALUE="NOTIONAL_AMOUNT", PROTECTION_VALUATION_APPROACH="OTHER", PROTECTION_MATURITY_DATE="2033-06-15", ORIGINAL_PROTECTION_VALUE="400000", ORIGINAL_PROTECTION_VALUE_DATE="2023-06-15"),
    "P003": dict(PROTECTION_PROVIDER="NL40001", TYPE_OF_PROTECTION="SECURITIES", PROTECTION_VALUE="2100000", TYPE_OF_PROTECTION_VALUE="MARKET_VALUE", PROTECTION_VALUATION_APPROACH="MARK_TO_MARKET", PROTECTION_VALUE_DATE="2026-09-30"),
}
# P001 secures two instruments - AnaCredit's n:m protection allocation
ALLOCATIONS = [("I001", "P001", "1000000", "0"), ("I002", "P001", "500000", "0"), ("I002", "P002", "400000", None), ("I005", "P003", "2100000", None)]
PDS = {"DE10001": "0.0072", "FR20001": "1.0", "DE10002": "0.0185", "DE10003": "0.0210", "NL40001": "0.0031"}
DEFAULTS = {"DE10001": ("NOT_IN_DEFAULT", None), "FR20001": ("DEFAULT_PAST_DUE", "2026-08-20"), "DE10002": ("NOT_IN_DEFAULT", None), "DE10003": ("NOT_IN_DEFAULT", None), "NL40001": ("NOT_IN_DEFAULT", None)}


def inst_id(i):
    contract = next(c for c, ii, _ in INSTRUMENTS if ii == i)
    return f"{BANK}-{contract}-{i}"


def build():
    login()
    if sql("select count(*) from category_scheme where artefact_id='CS_ANACREDIT'") != "0":
        sys.exit("CS_ANACREDIT already exists - aborting to avoid duplicates")

    codelist_ids = {}
    for artefact, (name, codes) in CODELISTS.items():
        post("/structures/codelists", dict(agencyId=AGENCY, artefactId=artefact, version=VERSION, name=name))
        cid = sql(f"select id from codelist where agency_id='{AGENCY}' and artefact_id='{artefact}'")
        for code, label in codes:
            post(f"/structures/codelists/{cid}/codes", dict(code=code, name=label))
        codelist_ids[artefact] = cid
    print(f"codelists: {len(codelist_ids)}")

    post("/structures/category-schemes", dict(agencyId=AGENCY, artefactId="CS_ANACREDIT", version=VERSION, name="AnaCredit"))
    scheme_id = sql("select id from category_scheme where artefact_id='CS_ANACREDIT'")
    post(f"/structures/category-schemes/{scheme_id}/categories", dict(parentCategoryId="", code="ANACREDIT", name="Analytical Credit Datasets (AnaCredit)"))
    category_id = sql(f"select id from category where category_scheme_id={scheme_id} and code='ANACREDIT'")

    flow_ids = {}
    for key, artefact, name, desc, _ in FLOWS:  # create all first so references can resolve
        loc = post("/microdata", dict(agencyId=AGENCY, artefactId=artefact, version=VERSION, name=name, description=desc))
        flow_ids[key] = loc.rstrip("/").split("/")[-1]
        post(f"/microdata/{flow_ids[key]}/categorisation", dict(categoryId=category_id))
    for key, _, _, _, fields in FLOWS:
        fid = flow_ids[key]
        for code, label, ftype, cl, required, facet in fields:
            if ftype.startswith("REF:"):
                data = dict(fieldCode=code, label=label, referencedFlowId=flow_ids[ftype[4:]])
                if required:
                    data["required"] = "true"
                if facet:
                    data["facetSlot"] = facet
                post(f"/microdata/{fid}/reference-fields", data)
            else:
                data = dict(fieldCode=code, label=label, dataType=ftype)
                if cl:
                    data["codelistId"] = codelist_ids[cl]
                if required:
                    data["required"] = "true"
                if facet:
                    data["facetSlot"] = facet
                post(f"/microdata/{fid}/fields", data)
    print(f"flows: {flow_ids}")
    return flow_ids


def ingest(flow_id, records):
    data = {"pluginIdentity": "anacredit-sample", "_csrf": csrf()}
    page = s.post(f"{BASE}/microdata/{flow_id}/ingest-token", data=data).text
    token = re.search(r"<code>([0-9a-f-]{36})</code>", page).group(1)
    body = {"records": [{"entityId": e, "reportingPeriod": PERIOD, "fields": f} for e, f in records]}
    r = requests.post(f"{BASE}/api/ingest/microdata/{flow_id}/records", json=body, headers={"X-Ingest-Token": token})
    if r.status_code != 202:
        sys.exit(f"ingest into {flow_id} failed: {r.status_code} {r.text}")
    post(f"/microdata/{flow_id}/records/promote", {})
    return len(records)


def load_sample(flow_ids):
    n = {}
    n["COUNTERPARTY_REFERENCE"] = sum(ingest(flow_ids["COUNTERPARTY_REFERENCE"], [cp]) for cp in CPS)
    n["INSTRUMENT"] = ingest(flow_ids["INSTRUMENT"], [
        (inst_id(i), dict(f, OBSERVED_AGENT=BANK, CONTRACT_ID=c, INSTRUMENT_ID=i)) for c, i, f in INSTRUMENTS])
    n["FINANCIAL"] = ingest(flow_ids["FINANCIAL"], [(inst_id(i), dict(f, INSTRUMENT=inst_id(i))) for i, f in FINANCIAL.items()])
    roles = []
    for i, debtors in DEBTORS.items():
        roles.append((f"{inst_id(i)}-{BANK}-CREDITOR", dict(INSTRUMENT=inst_id(i), COUNTERPARTY=BANK, COUNTERPARTY_ROLE="CREDITOR")))
        roles.append((f"{inst_id(i)}-{BANK}-SERVICER", dict(INSTRUMENT=inst_id(i), COUNTERPARTY=BANK, COUNTERPARTY_ROLE="SERVICER")))
        for d in debtors:
            roles.append((f"{inst_id(i)}-{d}-DEBTOR", dict(INSTRUMENT=inst_id(i), COUNTERPARTY=d, COUNTERPARTY_ROLE="DEBTOR")))
    n["COUNTERPARTY_INSTRUMENT"] = ingest(flow_ids["COUNTERPARTY_INSTRUMENT"], roles)
    n["JOINT_LIABILITIES"] = ingest(flow_ids["JOINT_LIABILITIES"], [
        (f"{inst_id('I004')}-{d}", dict(INSTRUMENT=inst_id("I004"), COUNTERPARTY=d, JOINT_LIABILITY_AMOUNT="291700")) for d in DEBTORS["I004"]])
    n["ACCOUNTING"] = ingest(flow_ids["ACCOUNTING"], [(inst_id(i), dict(f, INSTRUMENT=inst_id(i))) for i, f in ACCOUNTING.items()])
    n["PROTECTION_RECEIVED"] = ingest(flow_ids["PROTECTION_RECEIVED"], list(PROTECTIONS.items()))
    allocs = []
    for i, p, value, claims in ALLOCATIONS:
        f = dict(INSTRUMENT=inst_id(i), PROTECTION=p, PROTECTION_ALLOCATED_VALUE=value)
        if claims is not None:
            f["THIRD_PARTY_PRIORITY_CLAIMS"] = claims
        allocs.append((f"{inst_id(i)}-{p}", f))
    n["INSTRUMENT_PROTECTION"] = ingest(flow_ids["INSTRUMENT_PROTECTION"], allocs)
    n["COUNTERPARTY_RISK"] = ingest(flow_ids["COUNTERPARTY_RISK"], [(cp, dict(COUNTERPARTY=cp, PROBABILITY_OF_DEFAULT=pd)) for cp, pd in PDS.items()])
    dflt = []
    for cp, (status, date) in DEFAULTS.items():
        f = dict(COUNTERPARTY=cp, DEFAULT_STATUS=status)
        if date:
            f["DEFAULT_STATUS_DATE"] = date
        dflt.append((cp, f))
    n["COUNTERPARTY_DEFAULT"] = ingest(flow_ids["COUNTERPARTY_DEFAULT"], dflt)
    print("records:", json.dumps(n))


if __name__ == "__main__":
    load_sample(build())
